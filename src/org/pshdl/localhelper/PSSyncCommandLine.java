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

	public static class Configuration {
		public String workspaceID;
		public File workspaceDir;
		public File synplify;
		public File acttclsh;
		public File progammer;
		public String comPort;
	}

	public static void main(String[] args) throws ParseException, InterruptedException, IOException {
		final Configuration config = configure(args);
		if (config.workspaceID == null) {
			System.out.println("The workspace ID is a required option");
			printUsage();
			return;
		}
		if (config.workspaceDir == null) {
			config.workspaceDir = new File(".");
		}
		if (!config.workspaceDir.exists()) {
			config.workspaceDir.mkdirs();
		}

		final PSSyncCommandLine listener = new PSSyncCommandLine();
		final WorkspaceHelper wh = new WorkspaceHelper(listener, config.workspaceID, config.workspaceDir.getAbsolutePath(), config);
		listener.setWorkspaceHelper(wh);
		wh.connectTo(config.workspaceID);
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
		options.addOption(new Option("syn", "synplify", true, "Absolute path to the synplify executable." + printDefault(ActelSynthesis.SYNPLIFY)));
		options.addOption(new Option("atcl", "acttclsh", true, "Absolute path to the Actel TCL shell (acttclsh executable)." + printDefault(ActelSynthesis.ACTEL_TCLSH)));
		options.addOption(new Option("com", "comport", true, "The name or path to the serial port"));
		options.addOption(new Option("prg", "programmer", true, "The absolute path to the fpga_programmer executable." + printDefault(ConfigureInvoker.FPGA_PROGRAMMER)));
		options.addOption(new Option("h", "help", false, "Prints this help"));
		return options;
	}

	private static String printDefault(File executable) {
		return " Default is [" + executable + "]" + isFound(executable);
	}

	private static String isFound(File synplify) {
		if (synplify.exists() && synplify.canExecute())
			return " (found)";
		return " (not found)";
	}

	@Override
	public void connectionStatus(Status status) {
		System.out.println("CommandLine.connectionStatus()" + status);
		if (status == Status.CONNECTED) {
			try {
				workspaceHelper.announceServices();
			} catch (final IOException e) {
				e.printStackTrace();
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

	public static Configuration configure(String[] args) throws ParseException {
		final Configuration config = new Configuration();
		final PosixParser pp = new PosixParser();
		final CommandLine cli = pp.parse(options, args);
		if (cli.hasOption('h')) {
			printUsage();
			System.exit(1);
			return null;
		}
		config.workspaceID = cli.getOptionValue('w', null);
		if (cli.hasOption('d')) {
			config.workspaceDir = new File(cli.getOptionValue('d', null));
		}
		config.synplify = new File(cli.getOptionValue("syn", ActelSynthesis.SYNPLIFY.getAbsolutePath()));
		config.acttclsh = new File(cli.getOptionValue("atcl", ActelSynthesis.ACTEL_TCLSH.getAbsolutePath()));
		config.progammer = new File(cli.getOptionValue("prg", ConfigureInvoker.FPGA_PROGRAMMER.getAbsolutePath()));
		config.comPort = cli.getOptionValue("com", null);
		return config;
	}
}
