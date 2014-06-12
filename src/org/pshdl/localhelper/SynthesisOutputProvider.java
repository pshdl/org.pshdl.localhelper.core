package org.pshdl.localhelper;

import java.io.File;
import java.io.IOException;
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
import com.google.common.io.Files;

public class SynthesisOutputProvider implements IOutputProvider, IProgressReporter {
	private static final Map<String, ISynthesisTool> toolMap = Maps.newHashMap();
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
		subs = options.toArray(new MultiOption[options.size()]);
	}

	public Options getOptions() {
		final Options options = new Options();
		options.addOption("t", "tool", true, "Override the tool to use, available options are:" + toolMap.keySet()
				+ " by default the vendor specified in the synthesis settings is used");
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
		final ISynthesisTool tool = toolMap.get(vendor);
		if (tool == null)
			return "The tool:" + vendor + " is not known. Known tools are:" + toolMap.keySet();
		final File vhdlOutputDir = PStoVHDLCompiler.getOutputDir(cli);
		File outputDir = new File(vhdlOutputDir, "synthesis");
		if (cli.hasOption("synDir")) {
			outputDir = new File(cli.getOptionValue("synDir"));
		}
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
			tool.runSynthesis(topModule, wrappedModule, vhdlFiles, outputDir, board, settings, this, cli);
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
}
