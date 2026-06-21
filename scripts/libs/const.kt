#!/usr/bin/env kotlin

object Server {
    const val host = "fcos-ha"
    const val user = "core"
    val remoteTarget = "$user@$host"
}

object Nas {
    const val BASE_MOUNT_POINT = "/var/mnt/nas"

    val smartHomePath = "$BASE_MOUNT_POINT/smarthome"

    val homeAssistantPath = "$smartHomePath/homeassistant"
}
