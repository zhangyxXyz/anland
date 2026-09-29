package com.anland.design.backup

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WebDavClientTest {
    @Test fun `retention never deletes another app or a collection`() {
        MockWebServer().use { server ->
            server.start()
            val prefix="backup-com.anland.shell-"
            fun response(name: String,date: String,collection: Boolean=false)="""<d:response><d:href>/dav/$name</d:href><d:propstat><d:prop><d:getlastmodified>$date</d:getlastmodified><d:getcontentlength>9</d:getcontentlength><d:resourcetype>${if(collection) "<d:collection/>" else ""}</d:resourcetype></d:prop></d:propstat></d:response>"""
            val xml="""<d:multistatus xmlns:d="DAV:">${response(prefix+"new.zip","Wed, 30 Sep 2026 10:00:00 GMT")}${response(prefix+"old.zip","Tue, 29 Sep 2026 10:00:00 GMT")}${response("backup-com.anlandnext-other.zip","Mon, 28 Sep 2026 10:00:00 GMT")}${response(prefix+"folder.zip","Mon, 28 Sep 2026 10:00:00 GMT",true)}</d:multistatus>"""
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml));server.enqueue(MockResponse().setResponseCode(204))
            WebDavClient(WebDavConfig(server.url("/dav/").toString(),"user","password",directory=""),backupPrefix=prefix).prune(1)
            assertEquals("PROPFIND",server.takeRequest().method)
            val deletion=server.takeRequest();assertEquals("DELETE",deletion.method);assertEquals("/dav/${prefix}old.zip",deletion.path)
            assertEquals(2,server.requestCount)
        }
    }
    @Test fun `credentials and nested directory are sent to configured endpoint`() {
        MockWebServer().use { server ->
            server.start();repeat(2) { server.enqueue(MockResponse().setResponseCode(201)) };server.enqueue(MockResponse().setResponseCode(207))
            WebDavClient(WebDavConfig(server.url("/dav/").toString(),"u","p",directory="Anland/Shell")).test()
            val first=server.takeRequest();assertEquals("MKCOL",first.method);assertEquals("/dav/Anland/",first.path)
            assertEquals("Basic dTpw",first.getHeader("Authorization"));assertEquals("/dav/Anland/Shell/",server.takeRequest().path)
            assertEquals("1",server.takeRequest().getHeader("Depth"))
        }
    }
    @Test fun `server errors are not reported as a successful connection`() {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(401))
            assertThrows(IllegalStateException::class.java) { WebDavClient(WebDavConfig(server.url("/").toString(),"u","p",directory="")).test() }
        }
    }
}
