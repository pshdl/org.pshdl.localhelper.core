package org.pshdl.localhelper.actel;

import java.io.*;
import java.util.*;

import org.pshdl.localhelper.boards.*;
import org.pshdl.model.utils.internal.*;
import org.pshdl.rest.models.settings.*;

import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.io.*;

public class ActelSynthesis {
	private static String SYN_VERSION = System.getProperty("SYN_VERSION", "H201303MSP1-1");
	private static String LIBERO_PATH = System.getProperty("LIBERO_DIR", "c:\\Microsemi\\Libero_v11.2");
	public static File SYNPLICITY = new File(LIBERO_PATH, "Synopsys\\synplify_" + SYN_VERSION + "\\win64\\mbin\\synplify.exe");
	public static File ACTEL_TCLSH = new File(LIBERO_PATH, "Designer\\bin64\\acttclsh.exe");

	public static boolean isSynthesisAvailable() {
		System.out.println("Assuming SYN_VERSION to be: " + SYN_VERSION);
		System.out.println("Assuming LIBERO_PATH to be: " + LIBERO_PATH);
		if (!SYNPLICITY.exists()) {
			System.err.println("WARNING: Did not find synplicity at:" + SYNPLICITY);
			return false;
		}
		if (!ACTEL_TCLSH.exists()) {
			System.err.println("WARNING: Did not find Actel TCL shell (acttclsh) at:" + ACTEL_TCLSH);
			return false;
		}
		return true;
	}

	public static void main(String[] args) throws Exception {
		final String project = "TestProject";
		final String topModule = "RGBBlinkGame";
		final ArrayList<File> vhdlFiles = Lists.newArrayList(new File("RGBBlinkGame.vhdl"));
		final BoardSpecSettings spec = PSHDLBoardConfig.generateBoardSpec();
		PSHDLBoardConfig.generateExample(spec);
		final File synDir = new File(project);
		createSynthesisFiles(topModule, vhdlFiles, spec, synDir);
	}

	public static void createSynthesisFiles(final String topModule, final ArrayList<File> vhdlFiles, final BoardSpecSettings spec, final File synDir) throws IOException,
			FileNotFoundException {
		synDir.mkdir();
		generateBatFile(synDir, SYN_VERSION, LIBERO_PATH);
		generatePDCFile(synDir, topModule, spec, "clk", "reset_n");
		generateActelSynFile(synDir, topModule, topModule + "_constr");
		generateSynPrjFile(synDir, topModule, vhdlFiles);
		final FileOutputStream fos = new FileOutputStream(new File(synDir, "pshdl_pkg.vhd"));
		ByteStreams.copy(ActelSynthesis.class.getResourceAsStream("/pshdl_pkg.vhd"), fos);
		fos.close();
	}

	private static void generateSynPrjFile(File synDir, String topModule, List<File> vhdlFiles) throws IOException {
		final Map<String, String> options = Maps.newHashMap();
		options.put("{TOPNAME}", topModule);
		final StringBuilder sb = new StringBuilder();
		for (final File vhdlFile : vhdlFiles) {
			final String name = vhdlFile.getAbsolutePath().replaceAll("\\\\", "\\\\\\\\");
			sb.append("add_file -vhdl -lib work \"" + name + "\"\n");
		}
		options.put("{VHDL_FILES}", sb.toString());
		Files.write(Helper.processFile(ActelSynthesis.class, "syn.prj", options), new File(synDir, "syn.prj"));
	}

	private static void generateActelSynFile(File synDir, String topModule, String boardName) throws IOException {
		final Map<String, String> options = Maps.newHashMap();
		options.put("{TOPNAME}", topModule);
		options.put("{BOARD_NAME}", boardName);
		Files.write(Helper.processFile(ActelSynthesis.class, "ActelSynthScript.tcl", options), new File(synDir, "ActelSynthScript.tcl"));
	}

	private static void generatePDCFile(File synDir, String topName, BoardSpecSettings spec, String clockName, String rstName) throws IOException {
		final File pdcFile = new File(synDir, topName + "_constr.pdc");
		Files.write(spec.toString(clockName, rstName, new BoardSpecSettings.PDCWriter()), pdcFile, Charsets.UTF_8);
	}

	private static void generateBatFile(File synDir, String synversion, String liberopath) throws IOException {
		final Map<String, String> options = Maps.newHashMap();
		options.put("{LIBERO_PATH}", liberopath);
		options.put("{SYNPLICITY_VER}", synversion);
		Files.write(Helper.processFile(ActelSynthesis.class, "synth.bat", options), new File(synDir, "synth.bat"));
	}
}
