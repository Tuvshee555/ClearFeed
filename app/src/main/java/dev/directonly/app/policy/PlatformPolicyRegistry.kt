package dev.directonly.app.policy

import dev.directonly.app.model.SocialPlatform

class PlatformPolicyRegistry(
    private val instagram: DirectOnlyNavigationPolicy = DirectOnlyNavigationPolicy(),
    private val youtube: YouTubeNavigationPolicy = YouTubeNavigationPolicy(),
    private val facebook: FacebookNavigationPolicy = FacebookNavigationPolicy(),
) {
    fun forPlatform(platform: SocialPlatform): PlatformNavigationPolicy = when (platform) {
        SocialPlatform.INSTAGRAM -> instagram
        SocialPlatform.YOUTUBE -> youtube
        SocialPlatform.FACEBOOK -> facebook
    }
}
