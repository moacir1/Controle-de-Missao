#!/bin/bash

YAMCSADMIN_OPTS="$@"

mvn -q -f yamcs-core/pom.xml exec:exec \
    -Dexec.executable="java" \
    -Dexec.args="-classpath %classpath org.yamcs.cli.YamcsAdminCli $YAMCSADMIN_OPTS"

