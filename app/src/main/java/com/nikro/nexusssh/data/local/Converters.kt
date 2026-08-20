package com.nikro.nexusssh.data.local

import androidx.room.TypeConverter
import com.nikro.nexusssh.domain.model.BackspaceMode
import com.nikro.nexusssh.domain.model.ForwardType
import com.nikro.nexusssh.domain.model.Protocol
import com.nikro.nexusssh.domain.model.SshKeyType
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room type converters. Collections are persisted as compact JSON so the schema stays
 * stable while still supporting arbitrary environment variables and tag sets.
 */
class Converters {

    @TypeConverter
    fun stringListToJson(value: List<String>?): String =
        json.encodeToString(ListSerializer(String.serializer()), value.orEmpty())

    @TypeConverter
    fun jsonToStringList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(ListSerializer(String.serializer()), value) }.getOrDefault(emptyList())

    @TypeConverter
    fun stringMapToJson(value: Map<String, String>?): String =
        json.encodeToString(MapSerializer(String.serializer(), String.serializer()), value.orEmpty())

    @TypeConverter
    fun jsonToStringMap(value: String?): Map<String, String> =
        if (value.isNullOrBlank()) emptyMap()
        else runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), value)
        }.getOrDefault(emptyMap())

    @TypeConverter
    fun protocolToString(value: Protocol): String = value.name

    @TypeConverter
    fun stringToProtocol(value: String): Protocol =
        runCatching { Protocol.valueOf(value) }.getOrDefault(Protocol.SSH)

    @TypeConverter
    fun keyTypeToString(value: SshKeyType): String = value.name

    @TypeConverter
    fun stringToKeyType(value: String): SshKeyType =
        runCatching { SshKeyType.valueOf(value) }.getOrDefault(SshKeyType.UNKNOWN)

    @TypeConverter
    fun forwardTypeToString(value: ForwardType): String = value.name

    @TypeConverter
    fun stringToForwardType(value: String): ForwardType =
        runCatching { ForwardType.valueOf(value) }.getOrDefault(ForwardType.LOCAL)

    @TypeConverter
    fun backspaceModeToString(value: BackspaceMode): String = value.name

    @TypeConverter
    fun stringToBackspaceMode(value: String): BackspaceMode =
        runCatching { BackspaceMode.valueOf(value) }.getOrDefault(BackspaceMode.DELETE)

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
