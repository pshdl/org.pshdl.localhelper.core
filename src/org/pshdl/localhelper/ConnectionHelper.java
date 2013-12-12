package org.pshdl.localhelper;

import java.io.*;
import java.net.*;
import java.util.*;

import javax.ws.rs.client.*;
import javax.ws.rs.core.*;

import org.glassfish.jersey.apache.connector.*;
import org.glassfish.jersey.client.*;
import org.glassfish.jersey.media.multipart.*;
import org.glassfish.jersey.media.sse.*;
import org.pshdl.localhelper.WorkspaceHelper.FileOp;
import org.pshdl.localhelper.WorkspaceHelper.IWorkspaceListener;
import org.pshdl.localhelper.WorkspaceHelper.Severity;
import org.pshdl.rest.models.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.io.*;

public class ConnectionHelper {

	private final IWorkspaceListener listener;
	private final WorkspaceHelper wh;
	protected Client client;
	private static final ObjectReader repoReader = JSONHelper.getReader(RepoInfo.class);
	private static final ObjectReader messageReader = JSONHelper.getReader(Message.class);

	public static enum Status {
		CONNECTING, CONNECTED, CLOSED, RECONNECT, ERROR
	}

	public ConnectionHelper(IWorkspaceListener listener, WorkspaceHelper wh) {
		super();
		this.listener = listener;
		this.wh = wh;
	}

	public void downloadFile(File localFile, FileOp op, long lastModified, String name) {
		try {
			URL url;
			if (name.charAt(0) != '/') {
				url = new URL(getURL(wh.getWorkspaceID(), false) + "/" + name + "?plain=true");
			} else {
				url = new URL("http://" + SERVER + name + "?plain=true");
			}
			System.out.println("WorkspaceHelper.downloadFile()" + url);
			final InputStream os = url.openStream();
			ByteStreams.copy(os, Files.newOutputStreamSupplier(localFile));
			os.close();
			localFile.setLastModified(lastModified);
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

	public boolean isConnected() {
		if ((client == null) || (eventSource == null))
			return false;
		return eventSource.isOpen();
	}

	public String getURL(String workspaceID, boolean streaming) {
		if (streaming)
			return "http://" + SERVER + "/api/v0.1/streaming/workspace/" + workspaceID.toUpperCase();
		return "http://" + SERVER + "/api/v0.1/workspace/" + workspaceID.toUpperCase();

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
					final String clientID = getClientID(wid, client);
					estimateServerDelta();
					final RepoInfo repo = getRepoInfo(wid, client);
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
		if (Math.abs(diff) > 100) {
			this.serverDiff = diff;
			listener.doLog(Severity.INFO, "Server time difference is " + format(serverDiff));
		} else {
			this.serverDiff = 0;
		}
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
			f.format("%02ds ", m);
		}
		if ((m > 0) || (h > 0) || (s > 0)) {
			f.format("%02dm ", s);
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
		final WebTarget path = client.target(getURL(wid, true)).path(clientID).path("sse");
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
		final String repoInfo = client.target(getURL(wid, false)).request().accept(MediaType.APPLICATION_JSON).get(String.class);
		return repoReader.<RepoInfo> readValue(repoInfo);
	}

	public String getClientID(final String wid, Client client) {
		final WebTarget resource = client.target(getURL(wid, true));
		return resource.path("clientID").request().get(String.class);
	}

	public void uploadFile(File file, String workspaceID, String name) throws IOException {
		final FormDataMultiPart formDataMultiPart = new FormDataMultiPart();
		final FormDataContentDisposition dispo = FormDataContentDisposition//
				.name("file")//
				.fileName(name)//
				.size(file.length())//
				.modificationDate(new Date(file.lastModified())).build();
		formDataMultiPart.bodyPart(new FormDataBodyPart(dispo, Files.toString(file, Charsets.UTF_8)));
		final Client client = createClient(false);
		final Response response = client.target(getURL(workspaceID, false)).request(MediaType.TEXT_PLAIN_TYPE)
				.post(Entity.entity(formDataMultiPart, formDataMultiPart.getMediaType()));
		final int status = response.getStatus();
		if (status != 201) {
			listener.doLog(Severity.ERROR, "Failed to upload file:" + file + " status was:" + status);
		}

	}

	public void deleteFile(String workspaceID, String relPath) {
		final Client client = createClient(false);
		final Response response = client.target(getURL(workspaceID, false)).path("delete").path(relPath).request(MediaType.TEXT_PLAIN_TYPE).delete();
		final int status = response.getStatus();
		if (status != 200) {
			listener.doLog(Severity.ERROR, "Failed to delete file:" + relPath + " status was:" + status);
		}
	}
}
