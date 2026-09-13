package com.therealsoftware.duo

/**
 * Every socket the app uses moves with the channel number.
 *
 * Two pairs of phones on one network would otherwise fight over the same four
 * ports, and the second pair would fail for reasons that look like magic. One
 * number, set the same on both phones, keeps the pairs apart.
 *
 * Channel 0 uses 8900–8903, channel 1 uses 8910–8913, and so on. Ten channels
 * is far more than any home network needs.
 */
const val MAX_CHANNEL = 9

data class Ports(val channel: Int) {
    private val base: Int get() = 8900 + channel.coerceIn(0, MAX_CHANNEL) * 10

    /** The client shouts here to find a host. */
    val discovery: Int get() = base

    /** The two phones talk on this one. */
    val link: Int get() = base + 1

    /** The phone holding the clip serves it here. */
    val media: Int get() = base + 2

    /** The host sends rendered frames here. */
    val frames: Int get() = base + 3
}
