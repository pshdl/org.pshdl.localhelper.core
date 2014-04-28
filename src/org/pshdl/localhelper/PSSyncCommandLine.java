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
import java.util.prefs.Preferences;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.pshdl.localhelper.ConnectionHelper.Status;
import org.pshdl.localhelper.WorkspaceHelper.FileOp;
import org.pshdl.localhelper.WorkspaceHelper.IWorkspaceListener;
import org.pshdl.localhelper.WorkspaceHelper.Severity;
import org.pshdl.localhelper.actel.ActelSynthesis;
import org.pshdl.rest.models.Message;

import com.google.common.base.Splitter;

public class PSSyncCommandLine implements IWorkspaceListener {
	private static Options options = generateOptions();

	public static class Configuration {
		public String workspaceID;
		public String comPort;
		public File workspaceDir;
		public File synplify;
		public File acttclsh;
		public File progammer;

		public void loadFromPref(Preferences pref) {
			workspaceID = pref.get("workspaceID", null);
			comPort = pref.get("comPort", null);
			workspaceDir = new File(pref.get("workspaceDir", "."));
			synplify = new File(pref.get("synplify", ActelSynthesis.SYNPLIFY.getAbsolutePath()));
			acttclsh = new File(pref.get("acttclsh", ActelSynthesis.ACTEL_TCLSH.getAbsolutePath()));
			guessProgrammer(this, pref.get("progammer", ConfigureInvoker.FPGA_PROGRAMMER.getAbsolutePath()));
		}

		public void saveToPreferences(Preferences pref) {
			if (workspaceID != null) {
				pref.put("workspaceID", workspaceID);
			}
			if (comPort != null) {
				pref.put("comPort", comPort);
			}
			if (workspaceDir != null) {
				pref.put("workspaceDir", workspaceDir.getAbsolutePath());
			}
			if (synplify != null) {
				pref.put("synplify", synplify.getAbsolutePath());
			}
			if (acttclsh != null) {
				pref.put("acttclsh", acttclsh.getAbsolutePath());
			}
			if (progammer != null) {
				pref.put("progammer", progammer.getAbsolutePath());
			}
		}
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
		final String optionValue = cli.getOptionValue("prg", ConfigureInvoker.FPGA_PROGRAMMER.getAbsolutePath());
		guessProgrammer(config, optionValue);
		config.comPort = cli.getOptionValue("com", null);
		return config;
	}

	public static void guessProgrammer(final Configuration config, String optionValue) {
		config.progammer = new File(optionValue);
		if (!config.progammer.exists()) {
			final Iterable<String> properties = Splitter.on(':').trimResults().omitEmptyStrings().split(System.getProperty("java.library.path"));
			for (final String prop : properties) {
				final File file = new File(new File(prop), ConfigureInvoker.getExecutableName());
				if (file.exists()) {
					config.progammer = file;
					break;
				}
			}
		}
	}
}
