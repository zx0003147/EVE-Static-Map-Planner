package dev.evestaticmapplanner.localization

import java.util.Locale
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechRegion
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.sde.update.SdeUpdateComparison
import dev.evestaticmapplanner.sde.update.SdeUpdaterPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppLocalizationTest {
    @Test
    fun `locale tags are stable persistence values`() {
        assertEquals("en-US", AppLocale.EN_US.tag)
        assertEquals("zh-CN", AppLocale.ZH_CN.tag)
        assertEquals(AppLocale.EN_US, AppLocale.fromTagOrNull("en-US"))
        assertEquals(AppLocale.ZH_CN, AppLocale.fromTagOrNull("zh-CN"))
        assertEquals(null, AppLocale.fromTagOrNull("zh-Hans"))
    }

    @Test
    fun `English and Simplified Chinese catalogs expose the same complete typed structure`() {
        val english = AppStringsCatalog.forLocale(AppLocale.EN_US)
        val chinese = AppStringsCatalog.forLocale(AppLocale.ZH_CN)

        assertEquals(AppLocale.EN_US, english.locale)
        assertEquals(AppLocale.ZH_CN, chinese.locale)
        assertEquals(staticStrings(english).size, staticStrings(chinese).size)
        assertTrue(staticStrings(english).all(String::isNotBlank))
        assertTrue(staticStrings(chinese).all(String::isNotBlank))
    }

    @Test
    fun `parameterized strings preserve typed semantic parameters in both catalogs`() {
        assertEquals("Route found: 4 jumps", AppStringsCatalog.forLocale(AppLocale.EN_US).route.routeFound(4))
        assertEquals("已找到路线：4 跳", AppStringsCatalog.forLocale(AppLocale.ZH_CN).route.routeFound(4))

        val message = RouteFoundUiMessage(4)
        assertEquals("Route found: 4 jumps", message.resolve(AppStringsCatalog.forLocale(AppLocale.EN_US)))
        assertEquals("已找到路线：4 跳", message.resolve(AppStringsCatalog.forLocale(AppLocale.ZH_CN)))

        val segment = NavigationSegmentFailureUiMessage(
            NavigationStopUiRole.START,
            "Jita",
            NavigationStopUiRole.DESTINATION,
            "Amarr",
        )
        assertEquals(
            "Unable to calculate segment: Start Jita → Destination Amarr",
            segment.resolve(AppStringsCatalog.forLocale(AppLocale.EN_US)),
        )
        assertEquals(
            "无法计算路段：起点 Jita → 终点 Amarr",
            segment.resolve(AppStringsCatalog.forLocale(AppLocale.ZH_CN)),
        )
    }

    @Test
    fun `Preferences Provider Voice and Static Data catalogs are complete in both locales`() {
        AppLocale.entries.forEach { locale ->
            val strings = AppStringsCatalog.forLocale(locale)
            assertTrue(PreferencesText.entries.all { strings.preferences.text(it, "fixture", "fixture-2").isNotBlank() })
            assertTrue(VoiceInputProvider.entries.all { strings.preferences.voiceInputProvider(it).isNotBlank() })
            assertTrue(VoiceOutputProvider.entries.all { strings.preferences.voiceOutputProvider(it).isNotBlank() })
            assertTrue(AlibabaSpeechRegion.entries.all { strings.preferences.speechRegion(it).isNotBlank() })
            assertTrue(AiCredentialSource.entries.all {
                strings.preferences.credentialStatus("Provider", "ENV_KEY", it).isNotBlank()
            })
            assertTrue(PreferencesMessage.entries.all {
                strings.preferences.message(it, "fixture", "detail").isNotBlank()
            })
            assertTrue(SdeUpdaterPhase.entries.all { strings.staticData.phase(it, 3_466_501).isNotBlank() })
            assertTrue(SdeUpdateComparison.entries.all { strings.staticData.comparison(it).isNotBlank() })
        }
    }

    @Test
    fun `already-created semantic status resolves again in the current locale`() {
        val providerStatus = PreferencesUiMessage(PreferencesMessage.AI_SETTINGS_SAVED)
        val voiceStatus = PreferencesUiMessage(PreferencesMessage.VOICE_TEST_SUCCEEDED)
        val staticDataStatus = StaticDataUpdateFailedUiMessage("checksum mismatch")

        assertEquals(
            "Settings saved. Changing provider or model starts a new AI session.",
            providerStatus.resolve(AppStringsCatalog.forLocale(AppLocale.EN_US)),
        )
        assertEquals(
            "设置已保存。更改提供商或模型会启动新的 AI 会话。",
            providerStatus.resolve(AppStringsCatalog.forLocale(AppLocale.ZH_CN)),
        )
        assertEquals(
            "测试语音已成功播放。",
            voiceStatus.resolve(AppStringsCatalog.forLocale(AppLocale.ZH_CN)),
        )
        assertEquals(
            "静态数据更新失败。\nchecksum mismatch",
            staticDataStatus.resolve(AppStringsCatalog.forLocale(AppLocale.ZH_CN)),
        )
    }

    @Test
    fun `runtime localization changes English to Chinese and back without changing JVM locale`() {
        val jvmLocale = Locale.getDefault()
        val state = AppLocalizationState(AppLocale.EN_US)

        assertEquals("EVE Static Map Planner", state.state.value.strings.appTitle)
        assertEquals("Preferences", state.state.value.strings.preferences.title)

        state.updateLocale(AppLocale.ZH_CN)
        assertEquals("EVE 静态地图规划器", state.state.value.strings.appTitle)
        assertEquals("设置", state.state.value.strings.preferences.title)

        state.updateLocale(AppLocale.EN_US)
        assertEquals("EVE Static Map Planner", state.state.value.strings.appTitle)
        assertEquals("Preferences", state.state.value.strings.preferences.title)
        assertEquals(jvmLocale, Locale.getDefault())
    }

    @Test
    fun `changing application locale does not localize developer diagnostic formatting`() {
        val state = AppLocalizationState(AppLocale.EN_US)
        val before = String.format(Locale.ROOT, "diagnostic.value=%.2f", 1.5)

        state.updateLocale(AppLocale.ZH_CN)
        val after = String.format(Locale.ROOT, "diagnostic.value=%.2f", 1.5)

        assertEquals("diagnostic.value=1.50", before)
        assertEquals(before, after)
        assertNotEquals(state.state.value.strings.preferences.language, "Language")
    }

    private fun staticStrings(strings: AppStrings): List<String> = listOf(
        strings.appTitle,
        strings.common.run {
            listOf(ok, cancel, close, apply, add, clear, remove, update, rename, delete, select, unavailable, on, off)
        },
        strings.preferences.run { listOf(title, language, english, simplifiedChinese) },
        strings.staticData.run {
            listOf(
                setupTitle, noStaticDataInstalled, title, mode, managedDatabase, externalDatabase, database,
                currentBuild, latestBuild, lastChecked, status, notInstalled, notChecked, never,
                externalDatabaseWarning, unknownSize, checkForUpdates, installStaticData, downloadAndPrepare,
                cancel, discardPendingUpdate,
            )
        },
        strings.mainShell.run {
            listOf(
                marker, markerManager, sharedMarkerManager, clearAllTemporaryMarkers, markerSettings,
                miniMap, showMiniMap, hideMiniMap, miniMapSettings, preferences, openPreferences,
                staticData, openStaticData, keepWindowOnTop, disableAlwaysOnTop,
            )
        },
        strings.map.run {
            listOf(
                loadingStaticUniverse, unableToLoadMap, database, fitMap, resetView, renameView, viewName,
                viewNameValidation, toggleProjection, openEmbeddedAiAssistant, collapseSidebar, expandSidebar,
                official2DSelected, real3DSelected, addTemporaryMarker, addSavedMarker, editMarker,
                savePermanently, removeMarker, markersUnavailable, addSharedMarker, openSharedMarker,
                viewSharedMarker, addJumpRangeOverlay, setNormalStart, addNormalWaypoint, setNormalDestination,
                setCapitalStart, addCapitalWaypoint, setCapitalDestination, createWormholeConnection,
                wormholeConnections, viewerAccess, authenticationRequired, readOnly, notConnected, connecting,
                temporarilyReadOnly, offline, accessRemoved, incompatibleServer,
            )
        },
        strings.search.run { listOf(title, systemSearch, searchSystemPlaceholder) },
        strings.systemInfo.run {
            listOf(
                selectedSystem, noSystemSelected, loadingSystemDetails, systemId, region, constellation,
                securityStatus, stargates, ansiblex, jumpCoverage, ansiblexConnections, jumpOverlays,
                inSelectedOverlayIntersection, marker, sharedMarker, color, tags, notes,
                sharedMapDataMayBeStale, editSharedMarker, saved, temporary,
                bidirectional, outbound, inbound,
            )
        },
        strings.route.run {
            listOf(
                jumpRangeOverlays, normalRoute, capitalRoute, overlayOrigin, effectiveMaximumLy, intersect,
                start, destinationOptional, capitalStart, capitalDestinationOptional, calculate, calculating,
                needsRecalculation, useAnsiblex, useWormholes, showAnsiblexLayer, waypoints, waypointHint,
                routeActionsUnavailableForWormholes, stargateOnlyRoutingAvailable, validatesStaticCapitalRules,
                capitalLiveStateDisclaimer, phaseLabel, sameNormalSystem, normalRouteUnreachable,
                invalidNormalEndpoints, sameCapitalSystem, capitalRouteUnreachable, invalidCapitalEndpoints,
                draftOnly, succeeded, rejected, failed, unavailableSuffix, disconnectedUnavailable,
                selectTarget, publishNormalRoute, publishCapitalRoute, calculateBeforePublishing,
                connectSharedMapBeforePublishing, routeHandoffsUnsupported, publishPermissionRequired,
                publishingRoute, unableToLoadRouteGraph, unableToLoadCapitalRouteData,
                unableToLoadJumpOverlayData, jumpOverlayCalculationFailed, manualMaximumLyMustBeNumber,
                manualMaximumLyMustBePositive, addWaypointOrDestination, invalidNavigationStop,
                ansiblexDataUnavailable,
            )
        },
    )
        .flatMap { value -> if (value is List<*>) value.filterIsInstance<String>() else listOf(value as String) }
}
