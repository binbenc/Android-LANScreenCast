package com.benchan.wireless.screencasting.protocol.dlna

import com.benchan.wireless.screencasting.protocol.upnp.Soap
import com.benchan.wireless.screencasting.protocol.upnp.SoapException
import com.benchan.wireless.screencasting.protocol.upnp.UpnpError

import com.benchan.wireless.screencasting.core.common.WscLog
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

private val XML = ContentType.parse("text/xml; charset=\"utf-8\"")

/** DMR 的 HTTP 端点（设备描述、SCPD、SOAP 控制、GENA 订阅）。GENA 的非标准方法由 Ktor CIO 处理（单测验证）。 */
internal fun Application.dlnaModule(
    description: () -> String,
    actions: DmrActions,
    gena: GenaManager,
    initialEvent: (DlnaService) -> String,
) {
    routing {
        get("/dlna/description.xml") {
            call.respondText(description(), XML)
        }
        get("/dlna/{svc}/scpd.xml") {
            val svc = DlnaService.byPath(call.parameters["svc"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondText(DlnaDescriptions.scpd(svc), XML)
        }
        post("/dlna/{svc}/control") {
            val svc = DlnaService.byPath(call.parameters["svc"].orEmpty()) ?: return@post call.respond(HttpStatusCode.NotFound)
            val length = call.request.headers["Content-Length"]?.toLongOrNull()
            if (length != null && length > Soap.MAX_BODY) return@post call.respond(HttpStatusCode.PayloadTooLarge)
            try {
                val req = Soap.parse(call.receiveText(), call.request.headers["SOAPACTION"])
                val out = actions.handle(svc, req, call.request.origin.remoteAddress, call.request.headers["User-Agent"])
                call.response.header("EXT", "")
                call.respondText(Soap.response(svc.type, req.action, out), XML)
            } catch (e: SoapException) {
                WscLog.w("DlnaHttp", "${svc.path} fault ${e.error}")
                call.respondText(Soap.fault(e.error), XML, HttpStatusCode.InternalServerError)
            } catch (e: Exception) {
                WscLog.w("DlnaHttp", "${svc.path} error", e)
                call.respondText(Soap.fault(UpnpError.ACTION_FAILED), XML, HttpStatusCode.InternalServerError)
            }
        }
        route("/dlna/{svc}/event", HttpMethod("SUBSCRIBE")) {
            handle {
                val svc = DlnaService.byPath(call.parameters["svc"].orEmpty()) ?: return@handle call.respond(HttpStatusCode.NotFound)
                val sid = call.request.headers["SID"]
                val timeout = call.request.headers["TIMEOUT"]
                val sub = if (sid != null) {
                    gena.renew(sid, timeout) ?: return@handle call.respond(HttpStatusCode.PreconditionFailed)
                } else {
                    if (call.request.headers["NT"] != "upnp:event") return@handle call.respond(HttpStatusCode.PreconditionFailed)
                    gena.subscribe(svc, call.request.headers["CALLBACK"], timeout) ?: return@handle call.respond(HttpStatusCode.PreconditionFailed)
                }
                call.response.header("SID", sub.sid)
                call.response.header("TIMEOUT", "Second-${GenaManager.timeoutSeconds(timeout)}")
                call.response.header("SERVER", SsdpAdvertiser.SERVER)
                call.respond(HttpStatusCode.OK)
                if (sid == null) gena.sendInitial(sub, initialEvent(svc))
            }
        }
        route("/dlna/{svc}/event", HttpMethod("UNSUBSCRIBE")) {
            handle {
                val sid = call.request.headers["SID"] ?: return@handle call.respond(HttpStatusCode.PreconditionFailed)
                call.respond(if (gena.unsubscribe(sid)) HttpStatusCode.OK else HttpStatusCode.PreconditionFailed)
            }
        }
    }
}
