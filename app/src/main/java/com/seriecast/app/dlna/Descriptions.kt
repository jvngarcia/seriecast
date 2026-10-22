package com.seriecast.app.dlna

/** UPnP device description, SCPDs and the human-facing landing page. */
internal object Descriptions {

    fun device(friendlyName: String, uuid: String, includeUrlBase: Boolean, authority: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\"")
        append(" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">\n")
        append("  <specVersion><major>1</major><minor>0</minor></specVersion>\n")
        if (includeUrlBase) append("  <URLBase>http://").append(authority).append("/</URLBase>\n")
        append("  <device>\n")
        append("    <deviceType>").append(SsdpResponder.DEVICE_TYPE).append("</deviceType>\n")
        append("    <friendlyName>").append(escape(friendlyName)).append("</friendlyName>\n")
        append("    <manufacturer>SerieCast</manufacturer>\n")
        append("    <manufacturerURL>https://localhost/seriecast</manufacturerURL>\n")
        append("    <modelDescription>Servidor local de series para televisores DLNA</modelDescription>\n")
        append("    <modelName>SerieCast</modelName>\n")
        append("    <modelNumber>1.0</modelNumber>\n")
        append("    <modelURL>https://localhost/seriecast</modelURL>\n")
        append("    <serialNumber>").append(uuid.take(8)).append("</serialNumber>\n")
        append("    <UDN>uuid:").append(uuid).append("</UDN>\n")
        // X_DLNADOC tells DLNA-certified TVs this is a browseable media server, not a renderer.
        append("    <dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>\n")
        append("    <serviceList>\n")
        appendService(
            serviceType = SsdpResponder.CONTENT_DIRECTORY_SERVICE,
            serviceId = "urn:upnp-org:serviceId:ContentDirectory",
            scpd = "/scpd/contentdirectory.xml",
            control = "/control/contentdirectory",
            event = "/event/contentdirectory",
        )
        appendService(
            serviceType = SsdpResponder.CONNECTION_MANAGER_SERVICE,
            serviceId = "urn:upnp-org:serviceId:ConnectionManager",
            scpd = "/scpd/connectionmanager.xml",
            control = "/control/connectionmanager",
            event = "/event/connectionmanager",
        )
        append("    </serviceList>\n")
        append("  </device>\n")
        append("</root>\n")
    }

    private fun StringBuilder.appendService(serviceType: String, serviceId: String, scpd: String, control: String, event: String) {
        append("      <service>\n")
        append("        <serviceType>").append(serviceType).append("</serviceType>\n")
        append("        <serviceId>").append(serviceId).append("</serviceId>\n")
        append("        <SCPDURL>").append(scpd).append("</SCPDURL>\n")
        append("        <controlURL>").append(control).append("</controlURL>\n")
        append("        <eventSubURL>").append(event).append("</eventSubURL>\n")
        append("      </service>\n")
    }

    fun scpd(name: String): String? = when (name.lowercase()) {
        "contentdirectory.xml" -> CONTENT_DIRECTORY_SCPD
        "connectionmanager.xml" -> CONNECTION_MANAGER_SCPD
        else -> null
    }

    /** Declares exactly the actions we implement; a mismatch here makes some TVs hide the device. */
    private val CONTENT_DIRECTORY_SCPD = """
        <?xml version="1.0" encoding="utf-8"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <actionList>
            <action>
              <name>Browse</name>
              <argumentList>
                <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>
                <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>
                <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>
                <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>
                <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>
                <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>
                <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>
              </argumentList>
            </action>
            <action>
              <name>GetSearchCapabilities</name>
              <argumentList>
                <argument><name>SearchCaps</name><direction>out</direction><relatedStateVariable>SearchCapabilities</relatedStateVariable></argument>
              </argumentList>
            </action>
            <action>
              <name>GetSortCapabilities</name>
              <argumentList>
                <argument><name>SortCaps</name><direction>out</direction><relatedStateVariable>SortCapabilities</relatedStateVariable></argument>
              </argumentList>
            </action>
            <action>
              <name>GetSystemUpdateID</name>
              <argumentList>
                <argument><name>Id</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument>
              </argumentList>
            </action>
          </actionList>
          <serviceStateTable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType>
              <allowedValueList>
                <allowedValue>BrowseMetadata</allowedValue>
                <allowedValue>BrowseDirectChildren</allowedValue>
              </allowedValueList>
            </stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>SearchCapabilities</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>SortCapabilities</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="yes"><name>SystemUpdateID</name><dataType>ui4</dataType></stateVariable>
          </serviceStateTable>
        </scpd>
    """.trimIndent()

    private val CONNECTION_MANAGER_SCPD = """
        <?xml version="1.0" encoding="utf-8"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <actionList>
            <action>
              <name>GetProtocolInfo</name>
              <argumentList>
                <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
                <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
              </argumentList>
            </action>
            <action>
              <name>GetCurrentConnectionIDs</name>
              <argumentList>
                <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
              </argumentList>
            </action>
            <action>
              <name>GetCurrentConnectionInfo</name>
              <argumentList>
                <argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
                <argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>
                <argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>
                <argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>
                <argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>
                <argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
                <argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>
                <argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument>
              </argumentList>
            </action>
          </actionList>
          <serviceStateTable>
            <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="yes"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType>
              <allowedValueList>
                <allowedValue>OK</allowedValue>
                <allowedValue>ContentFormatMismatch</allowedValue>
                <allowedValue>InsufficientBandwidth</allowedValue>
                <allowedValue>UnreliableChannel</allowedValue>
                <allowedValue>Unknown</allowedValue>
              </allowedValueList>
            </stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType>
              <allowedValueList>
                <allowedValue>Input</allowedValue>
                <allowedValue>Output</allowedValue>
              </allowedValueList>
            </stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>
          </serviceStateTable>
        </scpd>
    """.trimIndent()

    fun indexPage(friendlyName: String, authority: String, port: Int, videos: Int): String = buildString {
        append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>SerieCast</title></head><body style=\"font-family:sans-serif;background:#101820;color:#eee;padding:24px\">")
        append("<h1>SerieCast</h1>")
        append("<p>Servidor DLNA activo en <code>http://").append(escape(authority)).append("/</code> (puerto ")
        append(port).append(")</p>")
        append("<p>Nombre publicado en la red: <strong>").append(escape(friendlyName)).append("</strong></p>")
        append("<p>Videos disponibles: ").append(videos).append("</p>")
        append("<p>Si ves esta pagina desde otro dispositivo, el televisor tambien puede alcanzar el servidor.")
        append(" El problema estaria en el descubrimiento SSDP (aislamiento de AP, red de invitados o firewall).</p>")
        append("</body></html>")
    }

    fun escape(value: String): String = buildString(value.length + 16) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
