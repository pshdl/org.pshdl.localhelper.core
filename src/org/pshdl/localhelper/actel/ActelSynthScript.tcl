set compile_directory     actel
set top_name              {TOPNAME}
set family                ProASIC3
set part                  UM4X4M1N
set package               "100 VQFP"

proc run_designer {message script} {
    puts "#!>$message"
    set f [open designer.tcl w]
    puts $f $script
    close $f
    puts [exec designer SCRIPT:designer.tcl]
}

run_designer "Creating new project" "
  new_design \
    -name $top_name \
    -family $family \
    -path .
  set_device \
    -die $part \
    -package \"$package\" \
    -speed STD \
    -voltage 1.5 \
    -iostd LVTTL \
    -jtag yes \
    -probe yes \
    -trst yes \
    -temprange COM \
    -voltrange COM
  import_source \
    -format edif \
    -edif_flavor GENERIC $top_name.edn \
    -format pdc \
    -abort_on_error yes {BOARD_NAME}.pdc \
    -merge_physical no \
    -merge_timing yes
  save_design $top_name.adb
"

run_designer "Compiling project" "
  open_design $top_name.adb
  compile \
    -pdc_abort_on_error on \
    -pdc_eco_display_unmatched_objects off \
    -pdc_eco_max_warnings 10000 \
    -demote_globals off \
    -demote_globals_max_fanout 12 \
    -promote_globals off \
    -promote_globals_min_fanout 200 \
    -promote_globals_max_limit 0 \
    -localclock_max_shared_instances 12 \
    -localclock_buffer_tree_max_fanout 12 \
    -combine_register off \
    -delete_buffer_tree off \
    -delete_buffer_tree_max_fanout 12 \
    -report_high_fanout_nets_limit 10
  save_design $top_name.adb
"

run_designer "Layouting project" "
  open_design $top_name.adb
  layout \
    -timing_driven \
    -run_placer on \
    -place_incremental off \
    -run_router on \
    -route_incremental OFF \
    -placer_high_effort off
  save_design $top_name.adb
"

run_designer "Exporting Dat file" "
  open_design $top_name.adb
  export \
    -format dc \
    -feature prog_fpga \
    $top_name.dat
  save_design $top_name.adb
"
exit