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

import org.apache.commons.cli.CommandLine;
import org.pshdl.localhelper.SynthesisInvoker.IProgressReporter;
import org.pshdl.model.utils.services.IOutputProvider.MultiOption;
import org.pshdl.rest.models.CompileInfo;
import org.pshdl.rest.models.settings.BoardSpecSettings;
import org.pshdl.rest.models.settings.SynthesisSettings;

/**
 * An interface for calling a vendor tool on the generated VHDL files. This
 * hooks directly into the synthesis Option of the PSHDLCompiler
 *
 * @author Karsten Becker
 * @since v0.1.83
 *
 */
public interface ISynthesisTool {

	/**
	 * The tool will be invoked with this method. While it is run in its own
	 * thread, it should spawn processes and optionally limit the execution
	 * time, so that it does not hang infinitely.
	 *
	 * @param topModule
	 *            the original name of the module that should be synthesized. It
	 *            can be found in any of the vhdlFiles
	 * @param wrappedModule
	 *            A wrapped version of the topModule. This will include
	 *            modifications such a static 1/0/open mappings, as well as
	 *            inversion when selected by the user
	 * @param vhdlFiles
	 *            All files that belong to this synthesis
	 * @param synDir
	 *            The directory where all generated files should be placed. Can
	 *            also be used for intermediate files
	 * @param board
	 *            The board specification as selected by the user
	 * @param settings
	 *            The settings as specified by the user
	 * @param reporter
	 *            This reporter can be used to communicate progress to the user.
	 *            It is also used to make available files known to the web
	 *            service
	 * @return <code>null</code> or a {@link CompileInfo} if the synthesis was
	 *         successful
	 * @throws Exception
	 *
	 * @since v0.1.83
	 */
	public CompileInfo runSynthesis(String topModule, String wrappedModule, Iterable<File> vhdlFiles, File synDir, BoardSpecSettings board, SynthesisSettings settings,
			IProgressReporter reporter, CommandLine cli) throws Exception;

	/**
	 * Return the name of the vendors that are supported by this tool
	 *
	 * @return the name of the vendors that are supported by this tool
	 *
	 * @since v0.1.83
	 */
	public String[] getSupportedFPGAVendors();

	/**
	 * Returns the supported command-line options of this tool
	 *
	 * @return the supported command-line options of this tool
	 *
	 * @since v0.1.83
	 */
	public MultiOption getOptions();

	/**
	 * Checks whether the tools can be found
	 *
	 * @return <code>true</code> when the tool can be used
	 *
	 * @since v0.1.84
	 */
	public boolean isSynthesisAvailable();
}
