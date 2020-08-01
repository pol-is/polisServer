#!/usr/bin/env bash

# cd top level
# cd `git rev-parse --show-toplevel`


# verify that we are in a polis repo
# git remote -v | grep -E "(polisServer.git|polis.git) .*(fetch)"
# use tail +4 client-report/config/config.js  | diff config/config.js -

# OUTPUT='blah blah (Status: 200)'
# if ! echo "$OUTPUT" | grep -q "(Status:\s200)"; then
#     echo "NO MATCH"
#     exit 1
# fi

# https://git-scm.com/book/en/v2/Customizing-Git-Git-Hooks
# rename .git/hooks/pre-commit.sample -> pre-commit

# TTD move next 4 lines into common_config.sh
# TTD make check_config.sh

static_node_directories=( client-admin client-participation client-report )
yaml_files=( development.yaml schema.yaml )
js_files=( config.js )
config_directory=config
repo_egrep="(polisServer.git|polis.git) .*(fetch)"


# use tail +4 client-report/config/config.js  | diff config/config.js -


for directory in "${static_node_directories[@]}"
do
    :
    mkdir ${directory}/${config_directory}
    for file in "${yaml_files[@]}"
    do
       : 
       echo "# PLEASE DO NOT EDIT THIS FILE" \
            > ${directory}/${config_directory}/${file}
       echo "# Please edit files in the top level config directory instead" \
            >> ${directory}/${config_directory}/${file}       
       echo "#" >> ${directory}/${config_directory}/${file}
       cat ${config_directory}/${file} >> ${directory}/${config_directory}/${file}
       echo "${file} -> ${directory}"
    done
    for file in "${js_files[@]}"
    do
       : 
       echo "// PLEASE DO NOT EDIT THIS FILE" \
            > ${directory}/${config_directory}/${file}
       echo "// Please edit files in the top level config directory instead" \
            >> ${directory}/${config_directory}/${file}
       echo "//" >> ${directory}/${config_directory}/${file}
       cat ${config_directory}/${file} >> ${directory}/${config_directory}/${file}
       echo "${file} -> ${directory}"
    done
done
