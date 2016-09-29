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
package org.pshdl.localhelper.xilinx;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.pshdl.localhelper.ISynthesisTool;
import org.pshdl.localhelper.JSONHelper;
import org.pshdl.localhelper.SynthesisInvoker;
import org.pshdl.localhelper.SynthesisInvoker.IProgressReporter;
import org.pshdl.model.utils.services.IOutputProvider.MultiOption;
import org.pshdl.rest.models.CompileInfo;
import org.pshdl.rest.models.FileRecord;
import org.pshdl.rest.models.FileType;
import org.pshdl.rest.models.ProgressFeedback.ProgressType;
import org.pshdl.rest.models.settings.BoardSpecSettings;
import org.pshdl.rest.models.settings.SynthesisSettings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.io.Files;

public class XilinxSynthesis implements ISynthesisTool {

	public static final String XILINX_VERSION = System.getProperty("XILINX_VERSION", "14.5");
	public static final File XILINX_XFLOW = new File(System.getProperty("XILINX_XFLOW", "C:\\Xilinx\\" + XILINX_VERSION + "\\ISE_DS\\ISE\\bin\\nt64\\xflow.exe"));

	@Override
	public CompileInfo runSynthesis(String topModule, final String wrappedModule, Iterable<File> vhdlFiles, File synDir, BoardSpecSettings board, SynthesisSettings settings,
			IProgressReporter reporter, CommandLine cli) throws Exception {
		int timeOut = 5;
		if ((cli != null) && cli.hasOption("to")) {
			timeOut = Integer.parseInt(cli.getOptionValue("to"));
			if (timeOut < 0) {
				timeOut = Integer.MAX_VALUE;
			}
		}
		final File[] oldFiles = synDir.listFiles(new FilenameFilter() {

			@Override
			public boolean accept(File dir, String name) {
				if (FileType.of(name) == FileType.vhdl)
					return false;
				return name.startsWith(wrappedModule);
			}
		});
		if (oldFiles != null) {
			for (final File oldFile : oldFiles) {
				java.nio.file.Files.delete(oldFile.toPath());
			}
		}
		reporter.reportProgress(ProgressType.progress, 0.1, "Invoking Synthesis");
		final String prjFileName = wrappedModule + ".prj";
		try (PrintStream ps = new PrintStream(new File(synDir, prjFileName), "UTF-8")) {
			for (final File file : vhdlFiles) {
				ps.printf("vhdl work %s%n", file.getAbsolutePath());
			}
		}
		final ProcessBuilder synProcessBuilder = new ProcessBuilder(//
				XILINX_XFLOW.getAbsolutePath(), //
				"-p", board.fpga.partNumber, //
				"-synth", "xst_mixed.opt", //
				prjFileName);
		final Process synProcess = SynthesisInvoker.runProcess(synDir, synProcessBuilder, timeOut, "synthesis", 0.2, 0.15, reporter);
		final CompileInfo info = new CompileInfo();
		info.setCreated(System.currentTimeMillis());
		info.setCreator(SynthesisInvoker.SYNTHESIS_CREATOR);
		sendXFlowLog(synDir, reporter, info, "synthesis.log");
		if (synProcess.exitValue() != 0) {
			reporter.reportProgress(ProgressType.error, null, "Synthesis did not exit normally, exit code was:" + synProcess.exitValue());
		} else {
			final ProcessBuilder implProcessBuilder = new ProcessBuilder(//
					XILINX_XFLOW.getAbsolutePath(), //
					"-p", board.fpga.partNumber, //
					"-implement", "balanced.opt", //
					wrappedModule);
			final Process implProcess = SynthesisInvoker.runProcess(synDir, implProcessBuilder, 2 * timeOut, "implementation", 0.4, 0.15, reporter);
			sendXFlowLog(synDir, reporter, info, "implementation.log");
			if (implProcess.exitValue() != 0) {
				reporter.reportProgress(ProgressType.error, null, "Implementation did not exit normally, exit code was:" + implProcess.exitValue());
			} else {
				final ProcessBuilder bitgenProcessBuilder = new ProcessBuilder(//
						XILINX_XFLOW.getAbsolutePath(), //
						"-p", board.fpga.partNumber, //
						"-config", "bitgen.opt", //
						wrappedModule);
				final Process bitGenProcess = SynthesisInvoker.runProcess(synDir, bitgenProcessBuilder, timeOut, "bitgen", 0.8, 0.15, reporter);
				sendXFlowLog(synDir, reporter, info, "bitgen.log");
				if (bitGenProcess.exitValue() != 0) {
					reporter.reportProgress(ProgressType.error, null, "Bit file generation did not exit normally, exit code was:" + bitGenProcess.exitValue());
				} else {
					final FileRecord record = reporter.reportFile(info, new File(synDir, wrappedModule + ".bit"), topModule + ".bit");
					reporter.reportProgress(ProgressType.progress, 1.0, "Bitstream creation succeeded!");
					final ObjectWriter writer = JSONHelper.getWriter();
					reporter.reportProgress(ProgressType.done, null, writer.writeValueAsString(record));
					return info;
				}
			}
		}
		return info;
	}

	public void sendXFlowLog(File synDir, IProgressReporter reporter, final CompileInfo info, final String synLog) throws IOException, JsonProcessingException {
		final File synLogFile = new File(synDir, synLog);
		Files.move(new File(synDir, "xflow.log"), synLogFile);
		final ObjectWriter writer = JSONHelper.getWriter();
		SynthesisInvoker.reportFile(reporter, info, writer, synLogFile, synLog);
	}

	@Override
	public String[] getSupportedFPGAVendors() {
		return new String[] { "Xilinx" };
	}

	@Override
	public MultiOption getOptions() {
		final Options options = new Options();
		options.addOption("so", "synOnly", false, "Synthesis only");
		options.addOption("to", "timeOut", true,
				"The maximum number of minutes the synthesis can take before it is cut off. Mapping can take twice as long. Default is [5]. Set to -1 to disable");
		return new MultiOption("The Xilinx tool has the following options", null, options);
	}

	@Override
	public boolean isSynthesisAvailable() {
		System.out.println("Assuming Xilinx xflow tool to be at:" + XILINX_XFLOW);
		if (!XILINX_XFLOW.exists()) {
			System.err.println("File " + XILINX_XFLOW
					+ " does not exist. You can specify it's location with the property XILINX_XFLOW or if Xilinx is installed in the default c:\\xilinx directory with XILINX_VERSION");
			return false;
		}
		return true;
	}

}
