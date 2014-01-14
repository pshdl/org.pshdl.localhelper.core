package org.pshdl.localhelper;

import java.io.*;
import java.net.*;
import java.util.*;

import org.pshdl.localhelper.ConnectionHelper.Status;
import org.pshdl.localhelper.PSSyncCommandLine.Configuration;
import org.pshdl.localhelper.actel.*;
import org.pshdl.rest.models.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.io.*;

public class WorkspaceHelper {

	public class ServiceAdvertiser implements MessageHandler<Void> {

		private final boolean synthesisAvailable;
		private final boolean hasBoard;

		public ServiceAdvertiser(boolean synthesisAvailable, boolean hasBoard) {
			this.synthesisAvailable = synthesisAvailable;
			this.hasBoard = hasBoard;
		}

		@Override
		public void handle(Message<Void> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID) throws Exception {
			doPost();

		}

		public void doPost() throws IOException {
			if (synthesisAvailable) {
				postMessage(Message.SYNTHESIS_AVAILABLE, null, null);
			}
			if (hasBoard) {
				postMessage(Message.BOARD_AVAILABLE, null, null);
			}
		}

	}

	private final class FileMonitor implements Runnable {
		public boolean stop = false;
		public File rootFolder;
		public URI rootURI;
		public Set<File> monitoredFiles = Sets.newCopyOnWriteArraySet();
		private final Set<String> extensions = Sets.newHashSet("pshdl", "vhd", "vhdl");

		public FileMonitor(File rootFolder) {
			super();
			this.rootFolder = rootFolder;
			this.rootURI = rootFolder.toURI();
		}

		@Override
		public void run() {
			try {
				System.out.println("WorkspaceHelper.FileMonitor.run() Monitoring on folder:" + rootFolder);
				while (!stop) {
					findMonitorFiles(rootFolder);
					for (int i = 0; i < 10; i++) {
						Thread.sleep(1000);
						if (stop)
							return;
						for (final File f : monitoredFiles) {
							handleLocalFile(f);
						}
					}
				}
			} catch (final InterruptedException e) {
			}
		}

		private void findMonitorFiles(File file) {
			// System.out.println("WorkspaceHelper.FileMonitor.findMonitorFiles()"
			// + file);
			final String extension = Files.getFileExtension(file.getName());
			if (file.isDirectory()) {
				if ("src-gen".equals(file.getName()))
					return;
				final File[] listFiles = file.listFiles();
				for (final File subFile : listFiles) {
					findMonitorFiles(subFile);
				}
			} else if (extensions.contains(extension.toLowerCase())) {
				if (monitoredFiles.add(file)) {
					handleLocalFile(file);
				}
			}
		}

		private void handleLocalFile(File file) {
			final String relPath = rootURI.relativize(file.toURI()).toString();
			if (!file.exists()) {
				ch.deleteFile(workspaceID, relPath);
				monitoredFiles.remove(file);
			} else {
				final FileInfo info = knownFiles.get(relPath);
				if (info != null) {
					final FileRecord record = info.getRecord();
					if (getModification(record) < file.lastModified()) {
						System.out.println("WorkspaceHelper.FileMonitor.findMonitorFiles() Uploading outdated file");
						try {
							ch.uploadFile(file, workspaceID, record.getRelPath());
							listener.fileOperation(FileOp.UPLOADED, file);
						} catch (final IOException e) {
							listener.doLog(e);
						}
					}
				} else {
					System.out.println("WorkspaceHelper.FileMonitor.findMonitorFiles() Uploading unknown file");
					try {
						ch.uploadFile(file, workspaceID, relPath);
						listener.fileOperation(FileOp.UPLOADED, file);
					} catch (final IOException e) {
						listener.doLog(e);
					}
				}
			}
		}
	}

	public static enum FileOp {
		ADDED, UPDATED, REMOVED, UPLOADED;
	}

	public static enum Severity {
		INFO, WARNING, ERROR;
	}

	public static interface IWorkspaceListener {
		public void connectionStatus(Status status);

		public void doLog(Severity severity, String message);

		public void incomingMessage(Message<?> message);

		public void fileOperation(FileOp op, File localFile);

		public void doLog(Exception e);
	}

	public static interface MessageHandler<T> {
		public void handle(Message<T> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID) throws Exception;
	}

	public class FileInfoArrayHandler implements MessageHandler<FileInfo[]> {

		@Override
		public void handle(Message<FileInfo[]> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID) throws Exception {
			final FileInfo[] readValues = getContent(msg, FileInfo[].class);
			for (final FileInfo fi : readValues) {
				handleFileInfo(fi);
			}
		}

	}

	public class FileInfoDeleteHandler implements MessageHandler<FileInfo> {

		@Override
		public void handle(Message<FileInfo> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID) throws Exception {
			final FileInfo fi = getContent(msg, FileInfo.class);
			final FileRecord record = fi.getRecord();
			final String relPath = record.getRelPath();
			final File localFile = new File(root, relPath);
			if (localFile.exists()) {
				if (localFile.lastModified() > getModification(record)) {
					listener.doLog(Severity.WARNING, "A file that existed locally is newer than a remotely deleted file:" + relPath);
				} else {
					localFile.delete();
					listener.fileOperation(FileOp.REMOVED, localFile);
				}
				final CompileInfo info = fi.getInfo();
				if (info != null) {
					deleteCompileInfoFiles(info);
				}
			} else {
				listener.doLog(Severity.WARNING, "A file that existed remotely but not locally has been deleted:" + relPath);
			}

		}

	}

	public class CompileContainerHandler implements MessageHandler<CompileInfo[]> {

		@Override
		public void handle(Message<CompileInfo[]> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID) throws Exception {
			final CompileInfo[] cc = getContent(msg, CompileInfo[].class);
			for (final CompileInfo ci : cc) {
				handleCompileInfo(ci);
			}
		}

	}

	private static final String WID_FILE = ".wid";
	private File root;
	private String workspaceID;
	private static final ObjectWriter writer = JSONHelper.getWriter();
	private final IWorkspaceListener listener;
	private final ConnectionHelper ch;
	private final Multimap<String, MessageHandler<?>> handlerMap = LinkedListMultimap.create();

	private static final ObjectMapper mapper = JSONHelper.getMapper();
	private FileMonitor fileMonitor;
	protected Map<String, FileInfo> knownFiles = Maps.newHashMap();
	private final Configuration config;
	private ServiceAdvertiser psa;

	public WorkspaceHelper(IWorkspaceListener listener, String workspaceID, String folder, Configuration config) {
		ActelSynthesis.ACTEL_TCLSH = config.acttclsh;
		ActelSynthesis.SYNPLIFY = config.synplify;
		this.config = config;
		if (workspaceID != null) {
			setWorkspaceID(workspaceID);
		}
		if (folder != null) {
			setWorkspace(folder);
		}
		this.listener = listener;
		this.ch = new ConnectionHelper(listener, this);
		registerFileSyncHandlers();
	}

	public void registerFileSyncHandlers() {
		handlerMap.put(Message.WORK_ADDED, new FileInfoArrayHandler());
		handlerMap.put(Message.WORK_UPDATED, new FileInfoArrayHandler());
		handlerMap.put(Message.WORK_DELETED, new FileInfoDeleteHandler());
		handlerMap.put(Message.COMPILER, new CompileContainerHandler());
		final boolean synthesisAvailable = ActelSynthesis.isSynthesisAvailable();
		final boolean hasBoard = config.comPort != null;
		psa = new ServiceAdvertiser(synthesisAvailable, hasBoard);
		handlerMap.put(Message.CLIENT_CONNECTED, psa);
		if (synthesisAvailable) {
			handlerMap.put(Message.SYNTHESIS_RUN, new SynthesisInvoker(ch));
		}
		if (hasBoard) {
			handlerMap.put(Message.BOARD_CONFIGURE, new ConfigureInvoker(ch, config));
		}
	}

	public void readWorkspaceID() {
		final File widFile = new File(root, WID_FILE);
		if (widFile.exists()) {
			try {
				final String wid = Files.toString(widFile, Charsets.UTF_8);
				validateWorkspaceID(wid);
			} catch (final IOException e) {
			}
		}
	}

	public void setWorkspace(String folder) {
		this.root = new File(folder);
		readWorkspaceID();
	}

	public void startFileMonitor() {
		if (fileMonitor != null) {
			fileMonitor.stop = true;
		}
		fileMonitor = new FileMonitor(root);
		new Thread(fileMonitor, "FileMonitor").start();
	}

	public String validateWorkspaceID(String wid) {
		wid = wid.trim();
		if (!wid.matches("[0-9a-fA-F]+"))
			return "The workspace ID should be a 16 digit hexadecimal number";
		setWorkspaceID(wid);
		return null;
	}

	private void setWorkspaceID(String wid) {
		wid = wid.toUpperCase();
		workspaceID = wid;
		try {
			Files.write(wid, new File(root, WID_FILE), Charsets.UTF_8);
		} catch (final IOException e) {
			listener.doLog(e);
		}
	}

	protected <T> void handleMessage(Message<T> message) {
		final String subject = message.subject;
		final Iterable<String> split = Splitter.on(':').split(subject);
		final StringBuilder sb = new StringBuilder();
		for (final String string : split) {
			sb.append(string);
			final String newSubject = sb.toString();
			final Collection<MessageHandler<?>> handlers = handlerMap.get(newSubject);
			for (final MessageHandler<?> messageHandler : handlers) {
				@SuppressWarnings("unchecked")
				final MessageHandler<T> handler = (MessageHandler<T>) messageHandler;
				try {
					handler.handle(message, listener, root, workspaceID);
				} catch (final Exception e) {
					listener.doLog(e);
				}
			}
			sb.append(':');
		}
	}

	private void deleteCompileInfoFiles(final CompileInfo info) {
		final List<FileRecord> outputs = info.getFiles();
		for (final FileRecord oi : outputs) {
			final File oF = new File(root, oi.getRelPath());
			deleteFileAndDir(root, oF);
		}
	}

	private void deleteFileAndDir(File srcGenDir, File oF) {
		if (srcGenDir.getAbsolutePath().equals(oF.getAbsolutePath()))
			return;
		oF.delete();
		if (oF.getParentFile().list().length == 0) {
			deleteFileAndDir(srcGenDir, oF.getParentFile());
		}
	}

	public void handleFileInfo(final FileInfo fi) {
		handleFileUpdate(fi.getRecord());
		knownFiles.put(fi.getRecord().getRelPath(), fi);
		final CompileInfo compileInfo = fi.getInfo();
		if (compileInfo != null) {
			handleCompileInfo(compileInfo);
		}
	}

	public void handleCompileInfo(final CompileInfo compileInfo) {
		final List<FileRecord> addOutputs = compileInfo.getFiles();
		for (final FileRecord outputInfo : addOutputs) {
			handleFileUpdate(outputInfo);
		}
	}

	public void handleFileUpdate(FileRecord fr) {
		final File localFile = new File(root, fr.getRelPath());
		final long lastModified = getModification(fr);
		final String uri = fr.getFileURI();
		if (localFile.exists()) {
			final long localLastModified = localFile.lastModified();
			if ((localLastModified < lastModified) || (lastModified == 0)) {
				ch.downloadFile(localFile, FileOp.UPDATED, lastModified, uri);
			} else {
				if (localLastModified != lastModified) {
					localFile.renameTo(new File(localFile.getParent(), localFile.getName() + "_conflict" + localLastModified));
					listener.doLog(Severity.WARNING, "The remote file was older than the local file. Created a backup of local file and used remote file");
					ch.downloadFile(localFile, FileOp.UPDATED, lastModified, uri);
				}
			}
		} else {
			final File parentFile = localFile.getParentFile();
			if (!parentFile.exists()) {
				parentFile.mkdirs();
			}
			ch.downloadFile(localFile, FileOp.ADDED, lastModified, uri);
		}
	}

	public static <T> T getContent(Message<?> message, Class<T> clazz) throws JsonProcessingException, IOException, JsonParseException, JsonMappingException {
		final Object json = message.contents;
		final String jsonString = writer.writeValueAsString(json);
		try {
			return mapper.readValue(jsonString, clazz);
		} catch (final Exception e) {
			System.out.println("WorkspaceHelper.getContent()" + jsonString);
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	public File getWorkspaceFolder() {
		return root;
	}

	public String getWorkspaceID() {
		return workspaceID;
	}

	public void closeConnection() {
		if (fileMonitor != null) {
			fileMonitor.stop = true;
			fileMonitor = null;
		}
		ch.closeConnection();
	}

	public void connectTo(String wid) throws IOException {
		ch.connectTo(wid);
	}

	private long getModification(FileRecord record) {
		return record.getLastModified() + ch.serverDiff;
	}

	public <T> void postMessage(String subject, String type, T content) throws IOException {
		ch.postMessage(subject, type, content);
	}

	public void announceServices() throws IOException {
		psa.doPost();
	}

}
