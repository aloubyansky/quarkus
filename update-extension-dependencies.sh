#!/bin/bash

# Purpose: Updates pom.xml files with symbolic dependencies to extensions to enforce a consistent build order.

set -e -u -o pipefail
shopt -s failglob

echo ''
echo 'Building bom-descriptor-json...'
echo ''
mvn clean package -f devtools/bom-descriptor-json

DEP_TEMPLATE='        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>XXX</artifactId>
            <version>\${project.version}</version>
            <type>pom</type>
            <scope>test</scope>
            <exclusions>
                <exclusion>
                    <groupId>*</groupId>
                    <artifactId>*</artifactId>
                </exclusion>
            </exclusions>
        </dependency>'

echo ''
echo 'Building dependencies list from bom-descriptor-json...'
echo ''
DEPS=`grep -Po '(?<="artifact-id": ")[^"]+' devtools/bom-descriptor-json/target/*.json \
  | sort \
  | xargs -i sh -c "echo \"${DEP_TEMPLATE}\" | sed 's/XXX/{}/'" \
  | sed ':a;N;$!ba;s/\n/\\\n/g'`    # replace newlines with \n so that sed accepts ${DEPS} as input

MARK_START='<!-- START update-extension-dependencies.sh -->'
MARK_END='<!-- END update-extension-dependencies.sh -->'
SED_EXPR="/${MARK_START}/,/${MARK_END}/c\        ${MARK_START}\n${DEPS}\n        ${MARK_END}"

echo ''
echo 'Updating devtools/bom-descriptor-json/pom.xml...'
echo ''
sed -i "${SED_EXPR}" devtools/bom-descriptor-json/pom.xml

echo ''
echo 'Updating docs/pom.xml...'
echo ''
sed -i "${SED_EXPR}" docs/pom.xml
