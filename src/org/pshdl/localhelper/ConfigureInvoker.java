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

import java.io.*;

import org.pshdl.localhelper.PSSyncCommandLine.Configuration;
import org.pshdl.localhelper.WorkspaceHelper.IWorkspaceListener;
import org.pshdl.localhelper.WorkspaceHelper.MessageHandler;
import org.pshdl.localhelper.WorkspaceHelper.Severity;
import org.pshdl.rest.models.*;
import org.pshdl.rest.models.ProgressFeedback.ProgressType;

public class ConfigureInvoker implements MessageHandler<FileRecord> {

	private final ConnectionHelper ch;
	private final Configuration config;
	private static String OS = System.getProperty("os.name").toLowerCase();
	public static File FPGA_PROGRAMMER = new File(System.getProperty("FPGA_PROGRAMMER", getExecutableName()));

	public static String getExecutableName() {
		if (OS.indexOf("win") >= 0)
			return "fpga_programmer.exe";
		return "fpga_programmer";
	}

	public ConfigureInvoker(ConnectionHelper ch, Configuration config) {
		this.ch = ch;
		this.config = config;
	}

	@Override
	public void handle(Message<FileRecord> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID) throws Exception {
		final FileRecord record = WorkspaceHelper.getContent(msg, FileRecord.class);
		final File datFile = new File(workspaceDir, record.relPath);
		final String datAbsolutePath = datFile.getAbsolutePath();
		if (!datFile.exists()) {
			listener.doLog(Severity.ERROR, "Unable to program file:" + record.relPath + " because it can not be found locally:" + datAbsolutePath);
			return;
		}
		final String absolutePath = config.progammer.getAbsolutePath();
		final String comPort = config.comPort;
		final ProcessBuilder pb = new ProcessBuilder(absolutePath, "-p", comPort, "-prg", datAbsolutePath);
		final Process configureProcess = runProcess(workspaceDir, pb, 2, "Programming", 0.1);
		if (configureProcess.exitValue() != 0) {
			sendMessage(ProgressType.error, null, "Programming the FPGA did not exit normally, exit code was:" + configureProcess.exitValue());
		} else {
			sendMessage(ProgressType.progress, 1.0, "FPGA configuration succeeded!");
			sendMessage(ProgressType.done, null, null);
		}
	}

	public Process runProcess(final File synDir, final ProcessBuilder processBuilder, int timeOutMinutes, String stage, final double progress) throws IOException,
	InterruptedException {
		processBuilder.redirectErrorStream(true);
		processBuilder.directory(synDir);
		final Process process = processBuilder.start();
		final InputStream is = process.getInputStream();
		final StringBuilder sb = new StringBuilder();
		new Thread(new Runnable() {
			@Override
			public void run() {
				final BufferedReader reader = new BufferedReader(new InputStreamReader(is));
				String line = null;
				double progressCounter = progress;
				final String absolutePath = synDir.getAbsolutePath();
				try {
					while ((line = reader.readLine()) != null) {
						line = line.replace(absolutePath, "");
						sb.append(line).append('\n');
						if (line.startsWith("#!>")) {
							sendMessage(ProgressType.progress, progressCounter, line.substring(3));
							progressCounter += 0.15;
						}
						System.out.println(line);
					}
				} catch (final IOException e) {
				}
			}
		}, "OutputLogger").start();
		if (!waitOrTerminate(process, timeOutMinutes)) {
			sendMessage(ProgressType.error, null, "Consumed more than " + timeOutMinutes + " minutes for " + stage);
		}
		if (!sb.toString().trim().isEmpty()) {
			sendMessage(ProgressType.output, null, sb.toString());
		}
		return process;
	}

	public void sendMessage(ProgressType type, Double progress, String message) throws IOException {
		final ProgressFeedback synProgress = new ProgressFeedback(type, progress, System.currentTimeMillis(), message);
		ch.postMessage(Message.BOARD_PROGRESS, "ProgressFeedback", synProgress);
		System.out.println("ConfigureInvoker.sendMessage()" + type + " " + message);
	}

	public boolean waitOrTerminate(final Process synProcess, int waitTime) throws InterruptedException {
		boolean done = false;
		for (int i = 0; i < (60 * waitTime); i++) {
			try {
				synProcess.exitValue();
				done = true;
				break;
			} catch (final Exception e) {
			}
			Thread.sleep(1000);
		}
		if (!done) {
			synProcess.destroy();
		}
		return done;
	}

}
