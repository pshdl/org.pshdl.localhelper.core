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
package org.pshdl.localhelper.actel;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pshdl.model.utils.internal.Helper;
import org.pshdl.rest.models.settings.BoardSpecSettings;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpec;
import org.pshdl.rest.models.settings.SynthesisSettings;

import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

public class ActelSynthesis {
	private static String SYN_VERSION = System.getProperty("SYN_VERSION", "H201303MSP1-1");
	private static String LIBERO_PATH = System.getProperty("LIBERO_DIR", "c:\\Microsemi\\Libero_v11.2");
	public static File SYNPLIFY = new File(LIBERO_PATH, "Synopsys\\synplify_" + SYN_VERSION + "\\win64\\mbin\\synplify.exe");
	public static File ACTEL_TCLSH = new File(LIBERO_PATH, "Designer\\bin64\\acttclsh.exe");

	public static boolean isSynthesisAvailable() {
		System.out.println("Assuming SYN_VERSION to be: " + SYN_VERSION);
		System.out.println("Assuming LIBERO_PATH to be: " + LIBERO_PATH);
		if (!SYNPLIFY.exists()) {
			System.err.println("WARNING: Did not find synplicity at:" + SYNPLIFY);
			return false;
		}
		if (!ACTEL_TCLSH.exists()) {
			System.err.println("WARNING: Did not find Actel TCL shell (acttclsh) at:" + ACTEL_TCLSH);
			return false;
		}
		return true;
	}

	public static void createSynthesisFiles(String topModule, final ArrayList<File> vhdlFiles, final BoardSpecSettings board, final File synDir, final File workspace,
			SynthesisSettings settings) throws IOException, FileNotFoundException {
		synDir.mkdirs();
		topModule = "Synthesis" + topModule + "Wrapper";
		generateBatFile(synDir, SYN_VERSION, LIBERO_PATH);
		generatePDCFile(synDir, topModule, settings, board, null, null);
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

	private static void generatePDCFile(File synDir, String topName, SynthesisSettings settings, BoardSpecSettings spec, String clockName, String rstName) throws IOException {
		final File pdcFile = new File(synDir, topName + "_constr.pdc");
		final String string = settings.toString(clockName, rstName, spec, new SynthesisSettings.PDCWriter());
		Files.write(string, pdcFile, StandardCharsets.UTF_8);
		if (pdcFile.length() == 0) {
			final File logFile = new File(synDir, "PDC_ERR_LOG.txt");
			try (final PrintStream ps = new PrintStream(logFile)) {
				System.err.println("The written PDC file: " + pdcFile + " turned out to be zero. This is unexpected. Writting log:" + logFile);
				ps.println("Output String was: " + string);
				ps.println("Clock: " + clockName + " Reset:" + rstName);
				ps.println("PinSpec:");
				for (final PinSpec over : settings.overrides) {
					ps.println(over);
				}
			}
		}
	}

	private static void generateBatFile(File synDir, String synversion, String liberopath) throws IOException {
		final Map<String, String> options = Maps.newHashMap();
		options.put("{SYNPLIFY_PATH}", ActelSynthesis.SYNPLIFY.getAbsolutePath());
		options.put("{ACTEL_PATH}", ActelSynthesis.ACTEL_TCLSH.getAbsolutePath());
		Files.write(Helper.processFile(ActelSynthesis.class, "synth.bat", options), new File(synDir, "synth.bat"));
	}
}
