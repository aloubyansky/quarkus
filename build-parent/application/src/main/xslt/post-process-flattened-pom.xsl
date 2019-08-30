<?xml version="1.0" encoding="utf-8"?>
<xsl:stylesheet version="2.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:m="http://maven.apache.org/POM/4.0.0"
    exclude-result-prefixes="m">
    <xsl:output method="xml" encoding="utf-8" indent="yes"
        xslt:indent-amount="2" xmlns:xslt="http://xml.apache.org/xslt" />
    <xsl:param name="graalvm.home"/>
    <xsl:param name="quarkus.version"/>
    <xsl:param name="surefire.version"/>
    <xsl:strip-space elements="*" />
    <xsl:template match="@*|node()">
        <xsl:copy>
            <xsl:apply-templates select="@*|node()" />
        </xsl:copy>
    </xsl:template>
    <xsl:template
        match="/m:project/m:properties/m:quarkus.version/text()[. = '${project.version}']">
        <xsl:value-of select="$quarkus.version"/>
    </xsl:template>
    <xsl:template
        match="/m:project/m:properties/m:surefire.version/text()[. = '${surefire-plugin.version}']">
        <xsl:value-of select="$surefire.version"/>
    </xsl:template>
    <xsl:template
        match="/m:project/m:properties/m:graalvm.home/text()">
        <xsl:text>${env.GRAALVM_HOME}</xsl:text>
    </xsl:template>
    <xsl:template
        match="/m:project/m:profiles/m:profile/m:build/m:plugins/m:plugin/m:version/text()[. = '${project.version}']">
        <xsl:value-of select="$quarkus.version"/>
    </xsl:template>
    <xsl:template match="/m:project/m:build/m:plugins/m:plugin[./m:artifactId = 'flatten-maven-plugin']"/>
    <xsl:template match="/m:project/m:build/m:plugins/m:plugin[./m:artifactId = 'xml-maven-plugin']"/>
</xsl:stylesheet>