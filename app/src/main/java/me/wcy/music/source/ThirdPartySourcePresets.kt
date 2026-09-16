package me.wcy.music.source

/**
 * 内置的常用音源直链，便于快速导入。
 *
 * 这些地址来自社区维护的音源集合，是否可用取决于音源服务端状态。
 */
object ThirdPartySourcePresets {
    data class Preset(
        val name: String,
        val url: String
    )

    private const val GH_PROXY = "https://ghproxy.net/"
    private const val PDONE = "raw.githubusercontent.com/pdone/lx-music-source/main"

    /** 直连地址（网络可达时优先）。 */
    val direct: List<Preset> = listOf(
        Preset("SixYin", "https://$PDONE/sixyin/latest.js"),
        Preset("Huibq", "https://$PDONE/huibq/latest.js"),
        Preset("Flower", "https://$PDONE/flower/latest.js"),
        Preset("LX", "https://$PDONE/lx/latest.js"),
        Preset("ikun", "https://$PDONE/ikun/latest.js"),
        Preset("Grass", "https://$PDONE/grass/latest.js"),
        Preset("JuheApi", "https://$PDONE/juhe/latest.js"),
        Preset(
            "SVIP音源",
            "https://raw.githubusercontent.com/LuoXiaohei-2025/LX-music-collection/" +
                "refs/heads/main/Source%20of%20music/SVIP%E9%9F%B3%E6%BA%90.js"
        )
    )

    /** 加速地址，国内网络更易访问。 */
    val accelerated: List<Preset> = listOf(
        Preset("SixYin", "${GH_PROXY}https://$PDONE/sixyin/latest.js"),
        Preset("Huibq", "${GH_PROXY}https://$PDONE/huibq/latest.js"),
        Preset("Flower", "${GH_PROXY}https://$PDONE/flower/latest.js"),
        Preset("LX", "${GH_PROXY}https://$PDONE/lx/latest.js"),
        Preset("ikun", "${GH_PROXY}https://$PDONE/ikun/latest.js"),
        Preset("Grass", "${GH_PROXY}https://$PDONE/grass/latest.js"),
        Preset("JuheApi", "${GH_PROXY}https://$PDONE/juhe/latest.js"),
        Preset(
            "SVIP音源",
            "${GH_PROXY}https://raw.githubusercontent.com/LuoXiaohei-2025/LX-music-collection/" +
                "refs/heads/main/Source%20of%20music/SVIP%E9%9F%B3%E6%BA%90.js"
        )
    )
}
