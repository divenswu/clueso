#!/bin/bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

java -cp $DIR/../lib/clueso-tool.jar com.scality.clueso.tools.LandingMetadataPopulatorTool $*