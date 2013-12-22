package org.pshdl.localhelper;

import java.io.*;

import org.apache.commons.cli.*;
import org.pshdl.localhelper.ConnectionHelper.Status;
import org.pshdl.localhelper.WorkspaceHelper.FileOp;
import org.pshdl.localhelper.WorkspaceHelper.IWorkspaceListener;
import org.pshdl.localhelper.WorkspaceHelper.Severity;
import org.pshdl.localhelper.actel.*;
import org.pshdl.rest.models.*;

public class PSSyncCommandLine implements IWorkspaceListener {
	private static Options options = generateOptions();

	public static void main(String[] args) throws ParseException, InterruptedException, IOException {
		final PosixParser pp = new PosixParser();
		final org.apache.commons.cli.CommandLine cli = pp.parse(options, args);
		if (cli.hasOption('h')) {
			printUsage();
			return;
		}
		if (!cli.hasOption('w')) {
			System.out.println("The workspace ID is a required option");
			printUsage();
			return;
		}
		final String workspaceID = cli.getOptionValue('w');
		final File workingDir = new File(cli.getOptionValue('d', "."));
		if (!workingDir.exists()) {
			workingDir.mkdirs();
		}

		final PSSyncCommandLine listener = new PSSyncCommandLine();
		final WorkspaceHelper wh = new WorkspaceHelper(listener, workspaceID, workingDir.getAbsolutePath());
		listener.setWorkspaceHelper(wh);
		wh.connectTo(workspaceID);
		while (true) {
			Thread.sleep(1000000);
		}
	}

	private WorkspaceHelper workspaceHelper;

	private void setWorkspaceHelper(WorkspaceHelper wh) {
		this.workspaceHelper = wh;
	}

	private static void printUsage() {
		final HelpFormatter hf = new HelpFormatter();
		hf.printHelp("pshdLocal", options);
	}

	public static Options generateOptions() {
		final Options options = new Options();
		options.addOption(new Option("w", "workspaceID", true, "The workspace ID to which the client should attach"));
		options.addOption(new Option("d", "dir", true, "Directory to use for the synced files. The default is the current directory"));
		options.addOption(new Option("h", "help", false, "Prints this help"));
		return options;
	}

	@Override
	public void connectionStatus(Status status) {
		System.out.println("CommandLine.connectionStatus()" + status);
		if (status == Status.CONNECTED) {
			if (ActelSynthesis.isSynthesisAvailable()) {
				try {
					workspaceHelper.postMessage(Message.SYNTHESIS_AVAILABLE, null, null);
				} catch (final IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	@Override
	public void doLog(Severity severity, String message) {
		System.out.println("CommandLine.doLog()" + severity + " " + message);
	}

	@Override
	public void incomingMessage(Message<?> message) {
		System.out.println("CommandLine.incomingMessage()" + message.subject + " from:" + message.clientID);
	}

	@Override
	public void fileOperation(FileOp op, File localFile) {
		System.out.println("CommandLine.fileOperation()" + op + " with " + localFile);
	}

	@Override
	public void doLog(Exception e) {
		e.printStackTrace();
	}
}
