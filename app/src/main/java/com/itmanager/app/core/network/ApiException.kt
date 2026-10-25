package com.itmanager.app.core.network

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/** An error already phrased for the user. */
open class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The NFC tag is already bound to another device; the user may choose to move it. */
class TagBoundElsewhereException(val deviceName: String?) : UserFacingException(
    "Метка уже привязана к " + (deviceName ?: "другому устройству") + ".",
)

/** A tag nobody has bound yet. */
class UnknownTagException : UserFacingException("Эта метка не привязана ни к одному устройству.")

/** Maps transport failures to messages that tell the user what to check. */
fun Throwable.toUserMessage(): String = when (this) {
    is UserFacingException -> message.orEmpty()
    is UnknownHostException, is ConnectException, is NoRouteToHostException ->
        "Сервер недоступен. Проверьте, что телефон в сети завода или VPN включён."
    is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException ->
        "Сервер не ответил вовремя. Проверьте сеть или VPN."
    is ClientRequestException -> when (response.status.value) {
        401, 403 -> "Сервер отклонил API-токен. Проверьте его в настройках."
        404 -> "На сервере нет такого раздела. Возможно, версия сервера устарела."
        else -> "Сервер отклонил запрос (код ${response.status.value})."
    }
    is ServerResponseException -> "Ошибка на сервере (код ${response.status.value})."
    is SerializationException -> "Сервер прислал неожиданный ответ."
    else -> "Не удалось загрузить данные: ${message ?: javaClass.simpleName}"
}

/** Like [runCatching], but never swallows coroutine cancellation. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
