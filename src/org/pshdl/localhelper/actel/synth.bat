@echo off
{LIBERO_PATH}\Synopsys\synplify_{SYNPLICITY_VER}\win64\mbin\synplify -batch -licensetype synplifypro_actel syn.prj
{LIBERO_PATH}\Designer\bin64\acttclsh.exe ActelSynthScript.tcl