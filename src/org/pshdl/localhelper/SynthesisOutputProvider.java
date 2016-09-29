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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.pshdl.generator.vhdl.PStoVHDLCompiler;
import org.pshdl.localhelper.SynthesisInvoker.IProgressReporter;
import org.pshdl.model.HDLPackage;
import org.pshdl.model.HDLUnit;
import org.pshdl.model.extensions.FullNameExtension;
import org.pshdl.model.utils.HDLCore;
import org.pshdl.model.utils.HDLQualifiedName;
import org.pshdl.model.utils.PSAbstractCompiler;
import org.pshdl.model.utils.PSAbstractCompiler.CompileResult;
import org.pshdl.model.utils.services.IOutputProvider;
import org.pshdl.rest.models.CompileInfo;
import org.pshdl.rest.models.FileRecord;
import org.pshdl.rest.models.FileType;
import org.pshdl.rest.models.ProgressFeedback.ProgressType;
import org.pshdl.rest.models.settings.BoardSpecSettings;
import org.pshdl.rest.models.settings.SynthesisSettings;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

public class SynthesisOutputProvider implements IOutputProvider, IProgressReporter {
	private static final Map<String, ISynthesisTool> toolMap = Maps.newLinkedHashMap();
	private static final MultiOption subs[];
	static {
		final ArrayList<MultiOption> options = Lists.newArrayList();
		final Collection<ISynthesisTool> tools = HDLCore.getAllImplementations(ISynthesisTool.class);
		for (final ISynthesisTool tool : tools) {
			for (final String vendor : tool.getSupportedFPGAVendors()) {
				toolMap.put(vendor.toLowerCase(), tool);
				final MultiOption option = tool.getOptions();
				if (option != null) {
					options.add(option);
				}
			}
		}
		try (PStoVHDLCompiler pStoVHDLCompiler = new PStoVHDLCompiler()) {
			options.add(pStoVHDLCompiler.getUsage());
		}
		subs = options.toArray(new MultiOption[options.size()]);
	}

	public Options getOptions() {
		final Options options = new Options();
		options.addOption("t", "tool", true,
				"Override the tool to use, available options are:" + toolMap.keySet() + " by default the vendor specified in the synthesis settings is used");
		options.addOption("s", "synFile", true, "Specify the synthesis settings file to use");
		options.addOption("synDir", true, "Specify the directory to which all synthesis related files are written, default is 'synthesis' below the VHDL output directory");
		return options;
	}

	@Override
	public String getHookName() {
		return "synthesis";
	}

	@Override
	public MultiOption getUsage() {
		return new MultiOption("synthesis usage: [OPTIONS] <files>", "In addition to that, the VHDL options are passed to the VHDL compiler.", getOptions(), subs);
	}

	@Override
	public String invoke(CommandLine cli) throws Exception {
		final String synFileOpt = cli.getOptionValue('s');
		if (synFileOpt == null)
			return "You need to specify a synthesis settings file";
		final File synFile = new File(synFileOpt);
		if (!synFile.exists())
			return "The file:" + synFile.getAbsolutePath() + " does not exist";
		final SynthesisSettings settings = JSONHelper.getReader(SynthesisSettings.class).readValue(synFile);
		final File boardFile = new File(synFile.getParentFile(), settings.board);
		if (!boardFile.exists())
			return "The file:" + boardFile.getAbsolutePath() + " does not exist";
		final BoardSpecSettings board = JSONHelper.getReader(BoardSpecSettings.class).readValue(boardFile);
		String vendor = board.fpga.vendor.toLowerCase();
		if (cli.hasOption('t')) {
			vendor = cli.getOptionValue('t');
		}
		final File vhdlOutputDir = PStoVHDLCompiler.getOutputDir(cli);
		File outputDir = new File(vhdlOutputDir, "synthesis");
		if (cli.hasOption("synDir")) {
			outputDir = new File(cli.getOptionValue("synDir"));
		}
		return runSynthesis(cli, settings, board, vendor, vhdlOutputDir, outputDir, this);
	}

	public static String runSynthesis(CommandLine cli, final SynthesisSettings settings, final BoardSpecSettings board, String vendor, final File vhdlOutputDir, File outputDir,
			IProgressReporter reporter) throws IOException, FileNotFoundException, Exception {
		final ISynthesisTool tool = toolMap.get(vendor);
		if (tool == null)
			return "The tool:" + vendor + " is not known. Known tools are:" + toolMap.keySet();
		if (!outputDir.exists()) {
			if (!outputDir.mkdirs())
				return "Failed to create output directory:" + outputDir.getAbsolutePath();
		}
		final String topModule = settings.topModule;
		System.out.println("Synthesis top module:" + topModule + " for board:" + board.boardName);
		try (final PStoVHDLCompiler vhdlCompiler = new PStoVHDLCompiler()) {
			final String invoke = vhdlCompiler.invoke(cli);
			if (invoke != null)
				return invoke;
			final Collection<HDLUnit> units = vhdlCompiler.getUnits();
			HDLUnit unit = null;
			for (final HDLUnit hdlUnit : units) {
				final HDLQualifiedName fqn = FullNameExtension.fullNameOf(hdlUnit);
				if (fqn.toString().equals(settings.topModule)) {
					unit = hdlUnit;
					break;
				}
			}
			if (unit == null)
				return "Did not find the module named:" + topModule;
			final HDLUnit wrapper = SynthesisInvoker.createSynthesisContainer(settings, unit).setLibURI(vhdlCompiler.uri);
			final String wrappedModule = SynthesisInvoker.getWrapperName(topModule);
			final CompileResult doCompile = vhdlCompiler.doCompile(wrappedModule + ".pshdl", new HDLPackage().addUnits(wrapper).setLibURI(vhdlCompiler.uri).copyDeepFrozen(null));
			PSAbstractCompiler.writeFiles(outputDir, doCompile);
			final List<File> vhdlFiles = Lists.newArrayList();
			for (final String srcName : vhdlCompiler.getSources()) {
				File srcFile = new File(srcName);
				if (FileType.of(srcName) == FileType.pshdl) {
					final String name = Files.getNameWithoutExtension(srcFile.getName()) + ".vhdl";
					srcFile = new File(vhdlOutputDir, name);
				}
				vhdlFiles.add(srcFile);
			}
			vhdlFiles.add(new File(outputDir, wrappedModule + ".vhdl"));
			final File pshdl_pkg = new File(outputDir, "pshdl_pkg.vhd");
			try (OutputStream os = new FileOutputStream(pshdl_pkg); InputStream is = WorkspaceHelper.class.getResourceAsStream("/pshdl_pkg.vhd")) {
				ByteStreams.copy(is, os);
			}
			vhdlFiles.add(0, pshdl_pkg);
			final CompileInfo compileInfo = tool.runSynthesis(topModule, wrappedModule, vhdlFiles, outputDir, board, settings, reporter, cli);
			if (compileInfo != null) {
				reporter.reportResult(compileInfo);
			}
		}
		return null;
	}

	@Override
	public void reportProgress(ProgressType type, Double progress, String message) throws IOException {
		String progressString = "";
		if (progress != null) {
			progressString = (int) (progress * 100) + "% ";
		}
		System.out.println(type + " " + progressString + message);
	}

	@Override
	public FileRecord reportFile(CompileInfo info, File datFile, String datRelPath) throws IOException {
		System.out.println("Wrote new File:" + datFile.getAbsolutePath() + " size:" + datFile.length());
		final FileRecord record = new FileRecord();
		record.fileURI = datFile.toURI().toString();
		record.lastModified = datFile.lastModified();
		record.relPath = datRelPath;
		record.hash = Files.asByteSource(datFile).hash(Hashing.sha1()).toString();
		info.getFiles().add(record);
		return record;
	}

	@Override
	public void reportResult(CompileInfo compileInfo) throws IOException {

	}
}
