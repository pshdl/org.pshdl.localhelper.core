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
import java.net.URI;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pshdl.localhelper.ConnectionHelper.Status;
import org.pshdl.localhelper.PSSyncCommandLine.Configuration;
import org.pshdl.localhelper.actel.ActelSynthesis;
import org.pshdl.model.utils.HDLCore;
import org.pshdl.rest.models.CompileInfo;
import org.pshdl.rest.models.FileInfo;
import org.pshdl.rest.models.FileRecord;
import org.pshdl.rest.models.Message;
import org.pshdl.rest.models.RepoInfo;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

public class WorkspaceHelper {

    public static final class ConsoleListener implements IWorkspaceListener {
        @Override
        public void incomingMessage(Message<?> message) {
            System.out.println("WorkspaceHelper.ConsoleListener.incomingMessage()" + message);
        }

        @Override
        public void fileOperation(FileOp op, File localFile) {
            System.out.println("WorkspaceHelper.ConsoleListener.fileOperation()" + op + " " + localFile);
        }

        @Override
        public void doLog(Exception e) {
            e.printStackTrace();
        }

        @Override
        public void doLog(Severity severity, String message) {
            System.out.println("WorkspaceHelper.ConsoleListener.doLog()" + severity + ":" + message);
        }

        @Override
        public void connectionStatus(Status status) {
            System.out.println("WorkspaceHelper.ConsoleListener.connectionStatus()" + status);
        }
    }

    public class ServiceAdvertiser implements MessageHandler<Void> {

        private final boolean synthesisAvailable;
        private final boolean hasBoard;

        public ServiceAdvertiser(boolean synthesisAvailable, boolean hasBoard) {
            this.synthesisAvailable = synthesisAvailable;
            this.hasBoard = hasBoard;
        }

        @Override
        public void handle(Message<Void> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID, RepoInfo repo) throws Exception {
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
        private final Set<String> extensions = Sets.newHashSet("pshdl", "vhd", "vhdl", "json");

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
                        if (stop) {
                            return;
                        }
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
                if ("src-gen".equals(file.getName())) {
                    return;
                }
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
                    final FileRecord record = info.record;
                    if (getModification(record) < file.lastModified()) {
                        System.out.println("WorkspaceHelper.FileMonitor.findMonitorFiles() Uploading outdated file");
                        try {
                            final String hash = Files.asByteSource(file).hash(Hashing.sha1()).toString();
                            if (hash.equalsIgnoreCase(record.hash)) {
                                System.out.println("WorkspaceHelper.FileMonitor.findMonitorFiles() Hash still fits, resetting modification stamp");
                                if (!file.setLastModified(getModification(record))) {
                                    listener.doLog(Severity.ERROR, "Failed to update time stamp on file:" + file);
                                }
                            } else {
                                ch.uploadFile(file, workspaceID, record.relPath);
                            }
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
        public void handle(Message<T> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID, RepoInfo info) throws Exception;
    }

    public class FileInfoArrayHandler implements MessageHandler<FileInfo[]> {

        @Override
        public void handle(Message<FileInfo[]> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID, RepoInfo info) throws Exception {
            final FileInfo[] readValues = getContent(msg, FileInfo[].class);
            for (final FileInfo fi : readValues) {
                handleFileInfo(fi);
            }
        }

    }

    public class RepoInfoHandler implements MessageHandler<RepoInfo> {

        @Override
        public void handle(Message<RepoInfo> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID, RepoInfo info) throws Exception {
            repo = getContent(msg, RepoInfo.class);
        }

    }

    public class FileInfoDeleteHandler implements MessageHandler<FileInfo> {

        @Override
        public void handle(Message<FileInfo> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID, RepoInfo repo) throws Exception {
            final FileInfo fi = getContent(msg, FileInfo.class);
            final FileRecord record = fi.record;
            final String relPath = record.relPath;
            final File localFile = new File(root, relPath);
            if (localFile.exists()) {
                if (localFile.lastModified() > getModification(record)) {
                    listener.doLog(Severity.WARNING, "A file that existed locally is newer than a remotely deleted file:" + relPath);
                } else {
                    if (!localFile.delete()) {
                        listener.doLog(Severity.ERROR, "Failed to delete file:" + localFile);
                    }
                    listener.fileOperation(FileOp.REMOVED, localFile);
                }
                final CompileInfo info = fi.info;
                if (info != null) {
                    deleteCompileInfoFiles(info);
                }
            } else {
                listener.doLog(Severity.WARNING, "A file that existed remotely but not locally has been deleted:" + relPath);
            }
            for (final Iterator<FileInfo> iterator = repo.getFiles().iterator(); iterator.hasNext();) {
                final FileInfo repoFI = iterator.next();
                if (repoFI.record.relPath.equals(relPath)) {
                    iterator.remove();
                }
            }
        }

    }

    public class CompileContainerHandler implements MessageHandler<CompileInfo[]> {

        @Override
        public void handle(Message<CompileInfo[]> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID, RepoInfo repo) throws Exception {
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
    private final Map<String, MessageHandler<?>> handlerMap = Maps.newLinkedHashMap();

    private static final ObjectMapper mapper = JSONHelper.getMapper();
    private FileMonitor fileMonitor;
    protected Map<String, FileInfo> knownFiles = Maps.newLinkedHashMap();
    private final Configuration config;
    private ServiceAdvertiser psa;
    private RepoInfo repo;

    public WorkspaceHelper(IWorkspaceListener listener, String workspaceID, String folder, Configuration config) {
        ActelSynthesis.ACTEL_TCLSH = config.acttclsh;
        ActelSynthesis.SYNPLIFY = config.synplify;
        this.config = config;
        if (listener != null) {
            this.listener = listener;
        } else {
            this.listener = new ConsoleListener();
        }
        if (workspaceID != null) {
            setWorkspaceID(workspaceID);
        }
        if (folder != null) {
            setWorkspace(folder);
        }
        this.ch = new ConnectionHelper(listener, this, config.secure);
        registerFileSyncHandlers();
    }

    public void registerFileSyncHandlers() {
        handlerMap.put(Message.WORK_ADDED, new FileInfoArrayHandler());
        handlerMap.put(Message.WORK_UPDATED, new FileInfoArrayHandler());
        handlerMap.put(Message.WORK_DELETED, new FileInfoDeleteHandler());
        handlerMap.put(Message.COMPILER, new CompileContainerHandler());
        handlerMap.put(Message.WORK_CREATED_WORKSPACE, new RepoInfoHandler());
        updateServices();
    }

    public void updateServices() {
        boolean synthesisAvailable = false;
        final Collection<ISynthesisTool> tools = HDLCore.getAllImplementations(ISynthesisTool.class);
        for (final ISynthesisTool synthesisTool : tools) {
            if (synthesisTool.isSynthesisAvailable()) {
                synthesisAvailable = true;
            }
        }
        final boolean hasBoard = config.comPort != null;
        psa = new ServiceAdvertiser(synthesisAvailable, hasBoard);
        handlerMap.put(Message.CLIENT_CONNECTED, psa);
        handlerMap.put(Message.SERVICE_DISCOVER, psa);
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
        if (!wid.matches("[0-9a-fA-F]+")) {
            return "The workspace ID should be a 16 digit hexadecimal number";
        }
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
            @SuppressWarnings("unchecked")
            final MessageHandler<T> handler = (MessageHandler<T>) handlerMap.get(newSubject);
            try {
                if (handler != null) {
                    handler.handle(message, listener, root, workspaceID, repo);
                }
            } catch (final Exception e) {
                listener.doLog(e);
            }
            sb.append(':');
        }
    }

    private void deleteCompileInfoFiles(final CompileInfo info) {
        final List<FileRecord> outputs = info.getFiles();
        for (final FileRecord oi : outputs) {
            final File oF = new File(root, oi.relPath);
            deleteFileAndDir(root, oF);
        }
    }

    private void deleteFileAndDir(File srcGenDir, File oF) {
        if (srcGenDir.getAbsolutePath().equals(oF.getAbsolutePath())) {
            return;
        }
        if (!oF.delete()) {
            listener.doLog(Severity.ERROR, "Failed to delete:" + oF);
        }
        if (oF.getParentFile().list().length == 0) {
            deleteFileAndDir(srcGenDir, oF.getParentFile());
        }
    }

    public void handleFileInfo(final FileInfo fi) throws IOException {
        handleFileUpdate(fi.record);
        updateRepoInfo(fi);
        knownFiles.put(fi.record.relPath, fi);
        final CompileInfo compileInfo = fi.info;
        if (compileInfo != null) {
            handleCompileInfo(compileInfo);
        }
    }

    public void updateRepoInfo(final FileInfo remoteFileInfo) {
        if (repo == null) {
            return;
        }
        final Set<FileInfo> repoFiles = repo.getFiles();
        boolean found = false;
        for (final Iterator<FileInfo> iterator = repoFiles.iterator(); iterator.hasNext();) {
            final FileInfo localFileInfo = iterator.next();
            if (localFileInfo.record.relPath.equals(remoteFileInfo.record.relPath)) {
                found = true;
                if (remoteFileInfo != localFileInfo) {
                    iterator.remove();
                }
            }
        }
        if (!found) {
            repoFiles.add(remoteFileInfo);
        }
    }

    public void handleCompileInfo(final CompileInfo compileInfo) throws IOException {
        final List<FileRecord> addOutputs = compileInfo.getFiles();
        for (final FileRecord outputInfo : addOutputs) {
            handleFileUpdate(outputInfo);
        }
    }

    public void handleFileUpdate(FileRecord fr) throws IOException {
        final File localFile = new File(root, fr.relPath);
        final long remoteLastModified = getModification(fr);
        final String uri = fr.fileURI;
        if (localFile.exists()) {
            final long localLastModified = localFile.lastModified();
            final String localHash = Files.asByteSource(localFile).hash(Hashing.sha1()).toString();
            if (fr.hash.equalsIgnoreCase(localHash)) {
                if (!localFile.setLastModified(remoteLastModified)) {
                    listener.doLog(Severity.ERROR, "Failed to updated modification timestamp on:" + localFile);
                }
            } else {
                if ((localLastModified < remoteLastModified) || (remoteLastModified == 0)) {
                    ch.downloadFile(localFile, FileOp.UPDATED, remoteLastModified, uri);
                } else {
                    final String newFileName = localFile.getName() + "_conflict" + localLastModified;
                    if (!localFile.renameTo(new File(localFile.getParent(), newFileName))) {
                        listener.doLog(Severity.ERROR, "Failed to rename file:" + localFile + " to " + newFileName);
                    }
                    listener.doLog(Severity.WARNING, "The remote file was older than the local file. Created a backup of local file and used remote file");
                    ch.downloadFile(localFile, FileOp.UPDATED, remoteLastModified, uri);
                    final String newlocalHash = Files.asByteSource(localFile).hash(Hashing.sha1()).toString();
                    System.out.println(newlocalHash);
                    System.out.println(localHash);
                }
            }
        } else {
            final File parentFile = localFile.getParentFile();
            if (!parentFile.exists()) {
                if (!parentFile.mkdirs()) {
                    listener.doLog(Severity.ERROR, "Failed to create directory:" + parentFile);
                }
            }
            ch.downloadFile(localFile, FileOp.ADDED, remoteLastModified, uri);
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
        return record.lastModified + ch.serverDiff;
    }

    public <T> void postMessage(String subject, String type, T content) throws IOException {
        ch.postMessage(subject, type, content);
    }

    public void announceServices() throws IOException {
        psa.doPost();
    }

    public String makeRelative(File localFile) {
        return root.toURI().relativize(localFile.toURI()).toString();
    }

    public void handleRepoInfo(RepoInfo info) {
        this.repo = info;
    }

}
