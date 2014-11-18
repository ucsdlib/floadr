<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    xmlns:dams="http://library.ucsd.edu/ontology/dams#"
    xmlns:d5="http://library.ucsd.edu/ontology/d5#"
    xmlns:mads="http://www.loc.gov/mads/rdf/v1#"
    xmlns:hydra="http://projecthydra.org/rights#"
    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:foaf="http://xmlns.com/foaf/0.1/"
    xmlns:fcrepo="http://fedora.info/definitions/v4/repository#"
    xmlns:ldp="http://www.w3.org/ns/ldp#"
    xmlns:premis="http://www.loc.gov/premis/rdf/v1#"
    xmlns:skos="http://www.w3.org/2004/02/skos/core#">

  <xsl:output method="xml"/>
  <xsl:variable name="oldns">http://library.ucsd.edu/ark:/20775</xsl:variable>
  <xsl:param name="repositoryURL">http://localhost:8080/rest</xsl:param>

  <!-- records ================================================================================ -->
  
  <xsl:template match="/rdf:RDF">
    <rdf:RDF>
      <xsl:apply-templates/>

      <!-- collections -->
      <xsl:for-each select="//dams:AssembledCollection|//dams:ProvenanceCollection|//dams:ProvenanceCollectionPart">
        <xsl:call-template name="collection"/>
      </xsl:for-each>
      <xsl:for-each select="//dams:Unit">
        <xsl:call-template name="unit"/>
      </xsl:for-each>

      <!-- subjects -->
      <xsl:for-each select="//mads:BuiltWorkPlace|//mads:ComplexSubject|//mads:ConferenceName|//mads:CorporateName|//mads:CulturalContext|//mads:FamilyName|//mads:Function|//mads:GenreForm|//mads:Geographic|//mads:Language|//mads:Iconography|//mads:Name|//mads:PersonalName|//mads:ScientificName|//mads:StylePeriod|//mads:Technique|//mads:Temporal|//mads:Topic">
        <xsl:call-template name="subject"/>
      </xsl:for-each>

      <!-- files -->
      <xsl:call-template name="files"/>

    </rdf:RDF>

  </xsl:template>

  <xsl:template match="dams:Object">
    <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
    <d5:Object rdf:about="{$id}">
      <xsl:apply-templates/>
      <rdf:type rdf:resource="http://fedora.info/definitions/v4/indexing#indexable"/>
      <d5:rights>
        <dc:RightsStatement rdf:about="{$id}/rights">
          <xsl:call-template name="rights-statement"/>
        </dc:RightsStatement>
      </d5:rights>
    </d5:Object>
  </xsl:template>

  <!-- collection records -->
  <xsl:template name="collection">
    <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
    <d5:Collection rdf:about="{$id}">
      <xsl:apply-templates/>
    </d5:Collection>
  </xsl:template>
  <xsl:template match="dams:hasAssembledCollection|dams:hasProvenanceCollection|dams:hasPart|dams:hasCollection">
    <xsl:for-each select="dams:AssembledCollection|dams:ProvenanceCollection|dams:ProvenanceCollectionPart">
      <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
      <d5:hasCollection rdf:resource="{$id}"/>
    </xsl:for-each>
  </xsl:template>
  <xsl:template match="dams:visibility">
    <!-- XXX does this map to rights? -->
    <d5:visibility><xsl:value-of select="."/></d5:visibility>
  </xsl:template>
  <xsl:template name="unit">
    <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
    <d5:Collection rdf:about="{$id}">
      <dc:title><xsl:value-of select="dams:unitName"/></dc:title>
      <dc:note><xsl:value-of select="dams:unitDescription"/></dc:note>
      <!-- <d5:relation rdf:resource="{dams:unitURI}"/> -->
    </d5:Collection>
  </xsl:template>

  <xsl:template name="subject">
    <xsl:if test="mads:authoritativeLabel">
      <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
      <skos:Concept rdf:about="{$id}">
        <skos:prefLabel><xsl:value-of select="mads:authoritativeLabel"/></skos:prefLabel>
        <xsl:for-each select="mads:isMemberOfMADSScheme/mads:MADSScheme/mads:hasExactExternalAuthority[@rdf:resource]">
          <!-- <skos:inScheme rdf:resource="{@rdf:resource}"/> -->
        </xsl:for-each>
        <!-- XXX concept type topic/geographic/etc.... -->
        <dc:type><xsl:value-of select="local-name()"/></dc:type>
      </skos:Concept>
    </xsl:if>
  </xsl:template>

  <!-- fields ================================================================================= -->

  <!-- cartographics -->
  <xsl:template match="dams:cartographics">
    <xsl:for-each select="dams:Cartographics">
      <d5:cartographics>
        <d5:Cartographics>
          <xsl:for-each select="*">
            <xsl:element name="d5:{local-name()}">
              <xsl:value-of select="."/>
            </xsl:element>
          </xsl:for-each>
        </d5:Cartographics>
      </d5:cartographics>
    </xsl:for-each>
  </xsl:template>

  <!-- collections -->
  <xsl:template match="dams:assembledCollection|dams:provenanceCollection|dams:provenanceCollectionPart">
    <xsl:for-each select="dams:AssembledCollection|dams:ProvenanceCollection|dams:ProvenanceCollectionPart">
      <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
      <d5:collection rdf:resource="{$id}"/>
    </xsl:for-each>
  </xsl:template>

  <!-- components -->
  <xsl:template match="dams:hasComponent">
    <xsl:for-each select="dams:Component">
      <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
      <d5:component>
        <d5:Component rdf:about="{$id}">
          <xsl:apply-templates/>
        </d5:Component>
      </d5:component>
    </xsl:for-each>
  </xsl:template>
  <xsl:template match="dams:order">
    <d5:order><xsl:value-of select="."/></d5:order>
  </xsl:template>

  <!-- dates -->
  <xsl:template match="dams:date">
    <xsl:for-each select="dams:Date">
      <xsl:choose>
        <xsl:when test="dams:type = 'creation' or dams:type = 'created'">
          <dc:created><xsl:value-of select="rdf:value"/></dc:created>
        </xsl:when>
        <xsl:when test="dams:type = 'collected' or dams:type = 'date collected'">
          <d5:dateCollected><xsl:value-of select="rdf:value"/></d5:dateCollected>
        </xsl:when>
        <xsl:when test="dams:type = 'event'">
          <d5:eventDate><xsl:value-of select="rdf:value"/></d5:eventDate>
        </xsl:when>
        <xsl:when test="dams:type = 'copyright'">
          <dc:dateCopyrighted><xsl:value-of select="rdf:value"/></dc:dateCopyrighted>
        </xsl:when>
        <xsl:otherwise>
          <dc:date><xsl:value-of select="rdf:value"/></dc:date>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:for-each>
  </xsl:template>

  <!-- events -->
  <xsl:template match="dams:event"/>

  <!-- files -->
  <xsl:template match="dams:hasFile"/>
  <xsl:template name="files">
    <xsl:for-each select="//dams:File">
      <xsl:variable name="fid" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
      <rdf:Description rdf:about="{$fid}/fcr:metadata">
        <d5:compositionLevel><xsl:value-of select="dams:compositionLevel"/></d5:compositionLevel>
        <d5:dateCreated><xsl:value-of select="dams:dateCreated"/></d5:dateCreated>
        <d5:formatName><xsl:value-of select="dams:formatName"/></d5:formatName>
        <d5:formatVersion><xsl:value-of select="dams:formatVersion"/></d5:formatVersion>
        <d5:objectCategory><xsl:value-of select="dams:objectCategory"/></d5:objectCategory>
        <d5:preservationLevel><xsl:value-of select="dams:preservationLevel"/></d5:preservationLevel>
        <d5:quality><xsl:value-of select="dams:quality"/></d5:quality>
        <d5:use><xsl:value-of select="dams:use"/></d5:use>
        <xsl:for-each select="dams:crc32checksum">
          <d5:digest>urn:crc32:<xsl:value-of select="."/></d5:digest>
        </xsl:for-each>
        <xsl:for-each select="dams:md5checksum">
          <d5:digest>urn:md5:<xsl:value-of select="."/></d5:digest>
        </xsl:for-each>
        <xsl:for-each select="dams:sha1checksum">
          <d5:digest>urn:sha1:<xsl:value-of select="."/></d5:digest>
        </xsl:for-each>
        <d5:mimeType><xsl:value-of select="dams:mimeType"/></d5:mimeType>
        <!-- <xsl:for-each select="dams:sourceFileName">
          <premis:hasOriginalName><xsl:value-of select="."/></premis:hasOriginalName>
        </xsl:for-each> -->
        <xsl:for-each select="dams:sourcePath">
          <d5:sourcePath><xsl:value-of select="."/></d5:sourcePath>
        </xsl:for-each>
        <d5:size><xsl:value-of select="dams:size"/></d5:size>
      </rdf:Description>
    </xsl:for-each>
  </xsl:template>

  <!-- mads subjects -->
  <xsl:template match="dams:builtWorkPlace|dams:conferenceName|dams:corporateName|dams:culturalContext|dams:familyName|dams:function|dams:genreForm|dams:geographic|dams:language|dams:iconography|dams:name|dams:otherName|dams:personalName|dams:scientificName|dams:stylePeriod|dams:technique|dams:temporal|dams:topic">
    <xsl:for-each select="*">
      <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
      <d5:subject rdf:resource="{$id}"/>
    </xsl:for-each>
  </xsl:template>
  <xsl:template match="dams:complexSubject">
    <xsl:for-each select="mads:ComplexSubject">
      <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
      <d5:subject rdf:resource="{$id}"/>
    </xsl:for-each>
  </xsl:template>

  <!-- notes -->
  <xsl:template match="dams:note">
    <xsl:for-each select="dams:Note">
      <xsl:choose>
        <xsl:when test="dams:type = 'identifier' and dams:displayLabel = 'ARK'">
          <d5:ark><xsl:value-of select="rdf:value"/></d5:ark>
        </xsl:when>
        <xsl:when test="dams:type = 'identifier'">
          <d5:identifier><xsl:value-of select="rdf:value"/></d5:identifier>
        </xsl:when>
        <xsl:when test="dams:type = 'extent'">
          <d5:extent><xsl:value-of select="rdf:value"/></d5:extent>
        </xsl:when>
        <xsl:when test="dams:type = 'preferred citation'">
          <d5:preferredCitation><xsl:value-of select="rdf:value"/></d5:preferredCitation>
        </xsl:when>
        <xsl:otherwise>
          <!-- TODO other notes types that need special handling? -->
          <d5:note><xsl:value-of select="rdf:value"/></d5:note>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:for-each>
  </xsl:template>
  <xsl:template match="dams:scopeContentNote">
    <d5:scopeAndContent>
      <xsl:value-of select="dams:ScopeContentNote/rdf:value"/>
    </d5:scopeAndContent>
  </xsl:template>

  <!-- related resources -->
  <xsl:template match="dams:relatedResource">
    <xsl:for-each select="dams:RelatedResource">
      <!-- link if rdf:about, inline if not -->
      <xsl:choose>
        <xsl:when test="@rdf:about">
          <xsl:variable name="tmp" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
          <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
          <d5:relatedResource rdf:resource="{$id}"/>
        </xsl:when>
        <xsl:otherwise>
          <d5:thumbnail rdf:resource="{dams:uri/@rdf:resource|dams:uri/text()}"/>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:for-each>
  </xsl:template>

  <!-- relationships -->
  <xsl:template match="dams:relationship">
    <xsl:for-each select="dams:Relationship">
      <xsl:variable name="roleid" select="dams:role/@rdf:resource"/>
      <xsl:variable name="rolename">
        <xsl:choose>
          <xsl:when test="dams:role/mads:Authority/mads:authoritativeLabel">
            <xsl:value-of select="dams:role/mads:Authority/mads:authoritativeLabel"/>
          </xsl:when>
          <xsl:when test="//mads:Authority[@rdf:about=$roleid]/mads:authoritativeLabel">
            <xsl:value-of select="//mads:Authority[@rdf:about=$roleid]/mads:authoritativeLabel"/>
          </xsl:when>
        </xsl:choose>
      </xsl:variable>
      <xsl:variable name="role">
        <xsl:choose>
          <xsl:when test="$rolename = 'Former owner'">formerOwner</xsl:when>
          <xsl:when test="$rolename = 'Principal investigator'">principalInvestigator</xsl:when>
          <xsl:when test="$rolename = 'Research team head'">researchTeamHead</xsl:when>
          <xsl:when test="$rolename = 'Research team member'">researchTeamMember</xsl:when>
          <!-- XXX: need better value processing... -->
          <xsl:when test="$rolename">
            <xsl:value-of select="translate($rolename, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ ()', 'abcdefghijklmnopqrstuvwxyz___')"/>
          </xsl:when>
          <xsl:otherwise>creator</xsl:otherwise>
        </xsl:choose>
      </xsl:variable>

      <xsl:variable name="name" select="dams:personalName/mads:PersonalName/@rdf:about|dams:corporateName/mads:CorporateName/@rdf:about|dams:conferenceName/mads:ConferenceName/@rdf:about|dams:familyName/mads:FamilyName/@rdf:about|dams:otherName/mads:Name/@rdf:about|dams:name/mads:Name/@rdf:about|dams:personalName/@rdf:resource|dams:corporateName/@rdf:resource|dams:conferenceName/@rdf:resource|dams:familyName/@rdf:resource|dams:otherName/@rdf:resource/dams:name/@rdf:resource"/>
      <xsl:variable name="nameid" select="concat($repositoryURL, substring-after($name, $oldns))"/>

      <xsl:element name="d5:{$role}">
        <xsl:attribute name="rdf:resource"><xsl:value-of select="$nameid"/></xsl:attribute>
      </xsl:element>
    </xsl:for-each>
  </xsl:template>

  <!-- rights -->

  <!-- suppress rights from main body (see rights statement) -->
  <xsl:template match="dams:copyright"/>
  <xsl:template match="dams:license"/>
  <xsl:template match="dams:otherRights"/>
  <xsl:template match="dams:rightsHolder"/>
  <xsl:template match="dams:rightsHolderCorporate"/>
  <xsl:template match="dams:rightsHolderPersonal"/>
  <xsl:template match="dams:statute"/>

  <!-- new hydra-plus rights statement -->
  <xsl:template name="rights-statement">
      <xsl:for-each select="dams:copyright/dams:Copyright">
        <xsl:for-each select="dams:copyrightJurisdiction">
          <premis:hasCopyrightJurisdiction><xsl:value-of select="."/></premis:hasCopyrightJurisdiction>
        </xsl:for-each>
        <xsl:for-each select="dams:copyrightStatus">
          <premis:hasCopyrightStatus><xsl:value-of select="."/></premis:hasCopyrightStatus>
        </xsl:for-each>
      </xsl:for-each>

      <xsl:for-each select="dams:license/dams:License">
        <xsl:choose>
          <xsl:when test="@rdf:about">
            <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
            <premis:hasLicenseTerms rdf:resource="{$id}"/>
          </xsl:when>
          <xsl:otherwise>
            <xsl:for-each select="dams:licenseNote">
              <premis:hasLicenseTerms><xsl:value-of select="."/></premis:hasLicenseTerms>
            </xsl:for-each>
            <xsl:for-each select="dams:restriction/dams:Restriction/dams:endDate">
              <hydra:embargoExpires><xsl:value-of select="."/></hydra:embargoExpires>
            </xsl:for-each>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>

      <xsl:for-each select="dams:otherRights/dams:OtherRights/dams:otherRightsBasis">
        <d5:otherRights><xsl:value-of select="."/></d5:otherRights>
      </xsl:for-each>

      <xsl:for-each select="dams:rightsHolder/*|dams:rightsHolderCorporate/*|dams:rightsHolderPersonal/*">
        <xsl:variable name="id" select="concat($repositoryURL, substring-after(@rdf:about, $oldns))"/>
        <d5:rightsHolder rdf:resource="{$id}"/>
      </xsl:for-each>

      <xsl:for-each select="dams:statute">
        <!-- we don't actually have this... -->
      </xsl:for-each>
  </xsl:template>

  <!-- titles -->
  <xsl:template match="dams:title">
    <xsl:for-each select="mads:Title">
      <xsl:for-each select="mads:elementList/mads:MainTitleElement">
        <dc:title><xsl:value-of select="mads:elementValue"/></dc:title>
      </xsl:for-each>
      <xsl:for-each select="mads:elementList/mads:SubTitleElement">
        <d5:subtitle><xsl:value-of select="mads:elementValue"/></d5:subtitle>
      </xsl:for-each>
      <xsl:for-each select="mads:elementList/mads:PartNameElement">
        <d5:partName><xsl:value-of select="mads:elementValue"/></d5:partName>
      </xsl:for-each>
      <xsl:for-each select="mads:elementList/mads:PartNumberElement">
        <d5:partNumber><xsl:value-of select="mads:elementValue"/></d5:partNumber>
      </xsl:for-each>
      <xsl:for-each select="mads:hasVariant/mads:Variant">
        <d5:alternateTitle><xsl:value-of select="mads:variantLabel"/></d5:alternateTitle>
      </xsl:for-each>
      <xsl:for-each select="mads:hasTranslationVariant/mads:Variant">
        <d5:translatedTitle><xsl:value-of select="mads:variantLabel"/></d5:translatedTitle>
      </xsl:for-each>
    </xsl:for-each>
  </xsl:template>

  <xsl:template match="dams:typeOfResource">
    <dc:type><xsl:value-of select="."/></dc:type>
  </xsl:template>

  <xsl:template match="dams:unit">
    <xsl:variable name="id">
      <xsl:choose>
        <xsl:when test="dams:Unit/@rdf:about != ''">
          <xsl:value-of select="concat($repositoryURL, substring-after(dams:Unit/@rdf:about, $oldns))"/>
        </xsl:when>
        <xsl:when test="@rdf:resource">
          <xsl:value-of select="concat($repositoryURL, substring-after(@rdf:resource, $oldns))"/>
        </xsl:when>
        <xsl:otherwise>
          <xsl:value-of select="concat($repositoryURL, '/XXXXXX')"/>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <d5:collection rdf:resource="{$id}"/>
  </xsl:template>

  <xsl:template match="*">
    <XXX><xsl:value-of select="name()"/></XXX>
  </xsl:template>

</xsl:stylesheet>
