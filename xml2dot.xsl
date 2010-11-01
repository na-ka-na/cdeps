<?xml version="1.0"?>

<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

<xsl:output method="text"/>
<xsl:template match="Something">
<Root-Element>
digraph g {
        graph [
	    rankdir = "LR"
	];
	node [
	    fontsize = "12"
	    fontname = "Courier"
	    shape = "ellipse"
	];
	edge[];
	<xsl:apply-templates select="Classes"/>
}
</Root-Element>
</xsl:template>

<xsl:template match="Classes">
    <xsl:apply-templates select="Class" mode="node"/>
</xsl:template>

<xsl:template match="Class" mode="node">
    <xsl:text>"</xsl:text><xsl:value-of select="@name"/> <xsl:text>" [
        label="</xsl:text><xsl:value-of
	select="@name"/>
	<xsl:text>"
	shape="record"
	color=".99 0.0 .9"
	style=filled
    ];
    </xsl:text>
    <xsl:apply-templates select="DependsUpon"/>
</xsl:template>

<xsl:template match="Class" mode="edge">
    <xsl:text>"</xsl:text><xsl:value-of select="../../@name"/> <xsl:text>" -&gt; "</xsl:text><xsl:value-of select="."/><xsl:text>"
    </xsl:text>
</xsl:template>

<xsl:template match="DependsUpon">
    <xsl:apply-templates select="Class" mode="edge"/>
</xsl:template>

</xsl:stylesheet>
