/*******************************************************************************
 * PSHDL is a library and (trans-)compiler for PSHDL input. It generates
 *     output suitable for implementation or simulation of it.
 *
 *     Copyright (C) 2014 Karsten Becker (feedback (at) pshdl (dot) org)
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *     This License does not grant permission to use the trade names, trademarks,
 *     service marks, or product names of the Licensor, except as required for
 *     reasonable and customary use in describing the origin of the Work.
 *
 * Contributors:
 *     Karsten Becker - initial API and implementation
 ******************************************************************************/
package org.pshdl.localhelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Formatter;
import java.util.Random;
import java.util.SortedSet;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.apache.connector.ApacheConnector;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.sse.EventSource;
import org.glassfish.jersey.media.sse.InboundEvent;
import org.glassfish.jersey.media.sse.SseFeature;
import org.pshdl.localhelper.WorkspaceHelper.FileOp;
import org.pshdl.localhelper.WorkspaceHelper.IWorkspaceListener;
import org.pshdl.localhelper.WorkspaceHelper.Severity;
import org.pshdl.rest.models.CompileInfo;
import org.pshdl.rest.models.FileInfo;
import org.pshdl.rest.models.Message;
import org.pshdl.rest.models.RepoInfo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

public class ConnectionHelper {

	private final IWorkspaceListener listener;
	private final WorkspaceHelper wh;
	protected Client client;
	protected String clientID;
	private final boolean secure;
	private static final ObjectReader repoReader = JSONHelper.getReader(RepoInfo.class);
	private static final ObjectReader messageReader = JSONHelper.getReader(Message.class);
	private static final ObjectWriter writer = JSONHelper.getWriter();

	public static enum Status {
		CONNECTING, CONNECTED, CLOSED, RECONNECT, ERROR
	}

	public ConnectionHelper(IWorkspaceListener listener, WorkspaceHelper wh, boolean secure) {
		super();
		this.listener = listener;
		this.wh = wh;
		this.secure = secure;
	}

	public void downloadFile(File localFile, FileOp op, long lastModified, String name) {
		try {
			URL url;
			if (name.charAt(0) != '/') {
				url = new URL(getURL(wh.getWorkspaceID(), false, secure) + "/" + name + "?plain=true");
			} else {
				url = new URL((secure ? "https://" : "http://") + SERVER + name + "?plain=true");
			}
			System.out.println("WorkspaceHelper.downloadFile()" + url);
			try (final InputStream os = url.openStream()) {
				ByteStreams.copy(os, Files.newOutputStreamSupplier(localFile));
			}
			if (!localFile.setLastModified(lastModified)) {
				listener.doLog(Severity.ERROR, "Failed to update modification timestamp on file:" + localFile);
			}
			listener.fileOperation(op, localFile);
		} catch (final Exception e) {
			listener.doLog(e);
		}
	}

	public void closeConnection() {
		if (client != null) {
			client.close();
			if (eventSource != null) {
				eventSource.close();
			}
			client = null;
			eventSource = null;
			listener.connectionStatus(Status.CLOSED);
		}
	}

	public <T> void postMessage(String subject, String type, T content) throws IOException {
		final Message<T> message = new Message<>(type, subject, content, clientID);
		final byte[] bytes = writer.writeValueAsBytes(message);
		final Client client = createClient(false);
		final Response response = client.target(getURL(wh.getWorkspaceID(), true, secure)).path(clientID).request().post(Entity.entity(bytes, MediaType.APPLICATION_JSON));
		final int status = response.getStatus();
		if (status != 204) {
			listener.doLog(Severity.ERROR, "Failed post message:" + new String(bytes, StandardCharsets.UTF_8) + " status was:" + status);
		}
	}

	public boolean isConnected() {
		if ((client == null) || (eventSource == null))
			return false;
		return eventSource.isOpen();
	}

	public String getURL(String workspaceID, boolean streaming, boolean secure) {
		final String protocol = secure ? "https://" : "http://";
		final String result;
		if (streaming) {
			result = protocol + SERVER + "/api/v0.1/streaming/workspace/" + workspaceID.toUpperCase();
		} else {
			result = protocol + SERVER + "/api/v0.1/workspace/" + workspaceID.toUpperCase();
		}
		return result;

	}

	private static String getServer() {
		final String property = System.getProperty("PSHDL_SERVER");
		if (property != null)
			return property;
		return "api.pshdl.org";
	}

	public void connectTo(final String wid) throws IOException {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					listener.connectionStatus(Status.CONNECTING);
					client = createClient(true);
					clientID = getClientID(wid, client);
					estimateServerDelta();
					final RepoInfo repo = getRepoInfo(wid, client);
					wh.handleRepoInfo(repo);
					for (final FileInfo fi : repo.getFiles()) {
						wh.handleFileInfo(fi);
					}
					connectToStream(wid, clientID);
				} catch (final Exception e) {
					listener.doLog(e);
					listener.connectionStatus(Status.ERROR);
				}
			}
		}, "connect").start();
	}

	private EventSource eventSource;
	public long serverDiff;
	private static final String SERVER = getServer();

	public void estimateServerDelta() {
		final Client timeClient = createClient(true);
		final WebTarget target = timeClient.target("http://" + SERVER + "/serverTime");
		final SortedSet<CData> cdata = Sets.newTreeSet();
		for (int i = 0; i < 5; i++) {
			final CData measurement = doChristianSync(target);
			cdata.add(measurement);
		}
		final long diff = cdata.first().diff;
		if (Math.abs(diff) > 10000) {
			listener.doLog(Severity.INFO, "Server time difference is " + format(serverDiff));
		}
		// Ignoring server time diff because we are hash based now
		this.serverDiff = 0;
	}

	private String format(long diff) {
		final boolean neg = diff < 0;
		diff = Math.abs(diff);
		final long ms = diff % 1000;
		diff /= 1000;
		final long s = diff % 60;
		diff /= 60;
		final long m = diff % 60;
		diff /= 60;
		final long h = diff;
		final Formatter f = new Formatter();
		if (neg) {
			f.format("-");
		}
		if (h > 0) {
			f.format("%dh ", h);
		}
		if ((m > 0) || (h > 0)) {
			f.format("%02dm ", m);
		}
		if ((m > 0) || (h > 0) || (s > 0)) {
			f.format("%02ds ", s);
		}
		f.format("%03dms", ms);
		final String string = f.toString();
		f.close();
		return string;
	}

	private static class CData implements Comparable<CData> {
		public final long rtt;
		public final long diff;

		public CData(long rtt, long diff) {
			super();
			this.rtt = rtt;
			this.diff = diff;
		}

		@Override
		public int compareTo(CData o) {
			return ComparisonChain.start().compare(rtt, o.rtt).compare(diff, o.diff).result();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = (prime * result) + (int) (diff ^ (diff >>> 32));
			result = (prime * result) + (int) (rtt ^ (rtt >>> 32));
			return result;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			final CData other = (CData) obj;
			if (diff != other.diff)
				return false;
			if (rtt != other.rtt)
				return false;
			return true;
		}
	}

	private CData doChristianSync(WebTarget target) {
		final long start = System.currentTimeMillis();
		final String repsonse = target.request().get(String.class);
		final long end = System.currentTimeMillis();
		final long serverTime = Long.parseLong(repsonse);
		return new CData(end - start, ((end + start) / 2) - serverTime);
	}

	public void connectToStream(final String wid, final String clientID) {
		if (client == null) {
			client = createClient(true);
		}
		final WebTarget path = client.target(getURL(wid, true, secure)).path(clientID).path("sse");
		System.out.println("WorkspaceHelper.connectToStream()" + path.getUri());
		try {
			eventSource = new EventSource(path) {
				@Override
				public void onEvent(InboundEvent inboundEvent) {
					final String message = inboundEvent.readData(String.class);
					try {
						final Message<?> readValue = messageReader.readValue(message);
						listener.incomingMessage(readValue);
						wh.handleMessage(readValue);
					} catch (final Exception e) {
						listener.doLog(e);
						listener.connectionStatus(Status.ERROR);
					}
				}
			};
			listener.connectionStatus(Status.CONNECTED);
			wh.startFileMonitor();
		} catch (final Exception e) {
			listener.doLog(e);
			listener.connectionStatus(Status.ERROR);
		}
	}

	public Client createClient(boolean apache) {
		final ClientConfig clientConfig = new ClientConfig();
		clientConfig.register(SseFeature.class);
		clientConfig.register(MultiPartFeature.class);
		if (apache) {
			clientConfig.connector(new ApacheConnector(clientConfig));
		}
		return ClientBuilder.newClient(clientConfig);
	}

	public RepoInfo getRepoInfo(final String wid, final Client client) throws IOException, JsonProcessingException {
		final String url = getURL(wid, false, secure);
		System.out.println("ConnectionHelper.getRepoInfo() Requesting:" + url);
		final String repoInfo = client.target(url).request().accept(MediaType.APPLICATION_JSON).get(String.class);
		return repoReader.<RepoInfo> readValue(repoInfo);
	}

	public String getClientID(final String wid, Client client) {
		final WebTarget resource = client.target(getURL(wid, true, secure));
		return resource.path("clientID").request().get(String.class);
	}

	private static final Random r = new Random();

	public void uploadFile(File file, String workspaceID, String name) throws IOException {
		try (final FormDataMultiPart formDataMultiPart = createFormBody(file, name)) {
			final Client client = createClient(false);
			final Response response = client.target(getURL(workspaceID, false, secure)).request(MediaType.TEXT_PLAIN_TYPE)
					.post(Entity.entity(formDataMultiPart, formDataMultiPart.getMediaType()));
			final int status = response.getStatus();
			if (status != 201) {
				listener.doLog(Severity.ERROR, "Failed to upload file:" + file + " status was:" + status);
			}
		}
	}

	public void deleteFile(String workspaceID, String relPath) {
		final Client client = createClient(false);
		final Response response = client.target(getURL(workspaceID, false, secure)).path("delete").path(relPath).request(MediaType.TEXT_PLAIN_TYPE).delete();
		final int status = response.getStatus();
		if (status != 200) {
			listener.doLog(Severity.ERROR, "Failed to delete file:" + relPath + " status was:" + status);
		}
	}

	public void uploadDerivedFile(File file, String workspaceID, String name, CompileInfo ci, String compileInfoSrc) throws IOException {
		try (final FormDataMultiPart formDataMultiPart = createFormBody(file, name)) {
			formDataMultiPart.field("applicationID", "PSHDLLocalClient");
			// Don't look at it! This is embarassing.. I promise I will
			// implement it
			// properly after the demo...
			formDataMultiPart.field("challenge", Long.toHexString(r.nextLong()));
			formDataMultiPart.field("signedChallenge", Long.toHexString(r.nextLong()));
			formDataMultiPart.field("compileInfo", writer.writeValueAsString(ci));
			formDataMultiPart.field("compileInfoSrc", compileInfoSrc);
			final Client client = createClient(false);
			final Response response = client.target(getURL(workspaceID, false, secure)).request(MediaType.TEXT_PLAIN_TYPE)
					.post(Entity.entity(formDataMultiPart, formDataMultiPart.getMediaType()));
			final int status = response.getStatus();
			if (status != 201) {
				listener.doLog(Severity.ERROR, "Failed to upload file:" + file + " status was:" + status + " " + response.readEntity(String.class));
			}
		}
	}

	public FormDataMultiPart createFormBody(File file, String name) throws IOException {
		final byte[] byteArray = Files.toByteArray(file);
		final FormDataMultiPart formDataMultiPart = new FormDataMultiPart();
		final FormDataContentDisposition dispo = FormDataContentDisposition//
				.name("file")//
				.fileName(name)//
				.size(file.length())//
				.modificationDate(new Date(file.lastModified())).build();
		formDataMultiPart.bodyPart(new FormDataBodyPart(dispo, byteArray, MediaType.APPLICATION_OCTET_STREAM_TYPE));
		formDataMultiPart.field("sha1", Hashing.sha1().hashBytes(byteArray).toString());
		return formDataMultiPart;
	}
}
