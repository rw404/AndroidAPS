"""Run selected production sources and real JUnit tests against reduced external API stubs.

This is a focused JVM harness, not an Android build, a device test, or a physiological
simulation. Serialization code and unrelated interface members are excluded.
"""

import argparse
from pathlib import Path
import re
import subprocess
import shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--gradle-lib', type=Path, required=True, help='Gradle 9.0.0 lib directory containing Kotlin 2.2.0 compiler jars')
parser.add_argument('--work-dir', type=Path, default=Path('/tmp/aaps-verification'), help='Contains downloaded jars and generated harness sources/classes/reports')
parser.add_argument('--java', default='java', help='Java 21 executable')
args = parser.parse_args()
ROOT = Path(__file__).resolve().parents[2]
TOOLS = args.work_dir.resolve()
TOOLS.mkdir(parents=True, exist_ok=True)
GRADLE_LIB = args.gradle_lib.resolve()
JAVA = args.java
GENERATED = TOOLS / 'generated-jvm-harness'
GENERATED.mkdir(exist_ok=True)
SRC = GENERATED / 'src'
if SRC.exists():
    shutil.rmtree(SRC)
SRC.mkdir()
CLASSES = GENERATED / 'classes'
if CLASSES.exists():
    shutil.rmtree(CLASSES)
CLASSES.mkdir()

def copy_source(relative):
    path = ROOT / relative
    shutil.copyfile(path, SRC / path.name)

def copy_dto(name):
    source = (ROOT / 'core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps' / f'{name}.kt').read_text()
    source = source.replace('import kotlinx.serialization.Serializable\n', '').replace('@Serializable\n', '')
    (SRC / f'{name}.kt').write_text(source)

copy_source('plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSSMB/DetermineBasalSMB.kt')
copy_source('plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSSMB/DetermineBasalSMBTest.kt')
copy_source('core/data/src/main/kotlin/app/aaps/core/data/configuration/Constants.kt')
copy_source('core/data/src/main/kotlin/app/aaps/core/data/model/GlucoseUnit.kt')
copy_source('core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/GlucoseStatus.kt')
for name in ['OapsProfile', 'CurrentTemp', 'IobTotal', 'MealData', 'AutosensResult', 'Predictions', 'GlucoseStatusSMB']:
    copy_dto(name)
rt = (ROOT / 'core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/RT.kt').read_text()
rt = 'package app.aaps.core.interfaces.aps\n\n' + rt[rt.index('data class RT('):rt.index(') {') + 1] + '\n'
rt = re.sub(r'    @Serializable\(with = \w+::class\)\n', '', rt)
(SRC / 'RT.kt').write_text(rt)
(SRC / 'APSResult.kt').write_text('package app.aaps.core.interfaces.aps\ninterface APSResult { enum class Algorithm { UNKNOWN, SMB } }\n')
(SRC / 'ProfileUtil.kt').write_text('''package app.aaps.core.interfaces.profile
import app.aaps.core.data.model.GlucoseUnit
interface ProfileUtil {
    val units: GlucoseUnit
    fun fromMgdlToStringInUnits(valueInMgdl: Double?, targetUnits: GlucoseUnit = units): String
}
''')
(SRC / 'FabricPrivacy.kt').write_text('''package app.aaps.core.interfaces.utils.fabric
interface FabricPrivacy { fun logCustom(event: String) }
''')


# Compile the exact command, callback/result implementation and test sources.
for relative in [
    'implementation/src/main/kotlin/app/aaps/implementation/queue/commands/CommandSMBBolus.kt',
    'implementation/src/test/kotlin/app/aaps/implementation/queue/commands/CommandSMBBolusTest.kt',
    'implementation/src/main/kotlin/app/aaps/implementation/pump/PumpEnactResultObject.kt',
    'core/interfaces/src/main/kotlin/app/aaps/core/interfaces/pump/PumpEnactResult.kt',
    'core/interfaces/src/main/kotlin/app/aaps/core/interfaces/pump/BolusProgressData.kt',
    'core/interfaces/src/main/kotlin/app/aaps/core/interfaces/queue/Callback.kt',
    'core/interfaces/src/main/kotlin/app/aaps/core/interfaces/queue/Command.kt',
    'core/data/src/main/kotlin/app/aaps/core/data/time/T.kt',
    'core/interfaces/src/main/kotlin/app/aaps/core/interfaces/notifications/Notification.kt',
    'plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/notifications/NotificationDeliveryPolicy.kt',
    'plugins/main/src/test/kotlin/app/aaps/plugins/main/general/overview/notifications/NotificationDeliveryPolicyTest.kt',
    'app/src/main/kotlin/app/aaps/receivers/PumpConnectionReminderPolicy.kt',
    'app/src/test/kotlin/app/aaps/receivers/PumpConnectionReminderPolicyTest.kt',
    'app/src/main/kotlin/app/aaps/receivers/PumpConnectionRecovery.kt',
    'app/src/test/kotlin/app/aaps/receivers/PumpConnectionRecoveryTest.kt',
]:
    copy_source(relative)

# Stubs model external contracts only. Their implementations do not decide doses or guards.
external_apis = {
    'Context.kt': 'package android.content\nopen class Context',
    'RawRes.kt': 'package androidx.annotation\nannotation class RawRes',
    'BS.kt': 'package app.aaps.core.data.model\ndata class BS(val timestamp: Long, val amount: Double, val type: Type) { enum class Type { NORMAL, SMB, PRIMING } }',
    'PersistenceLayer.kt': 'package app.aaps.core.interfaces.db\nimport app.aaps.core.data.model.BS\ninterface PersistenceLayer { fun getNewestBolus(): BS? }',
    'DetailedBolusInfo.kt': 'package app.aaps.core.interfaces.pump\nimport app.aaps.core.data.model.BS\nclass DetailedBolusInfo { var insulin = 0.0; var bolusType = BS.Type.NORMAL; var lastKnownBolusTime = 0L; var deliverAtTheLatest = 0L }',
    'Pump.kt': '''package app.aaps.core.interfaces.pump
interface Pump {
    fun deliverTreatment(info: DetailedBolusInfo): PumpEnactResult
    fun isInitialized(): Boolean
    fun isBusy(): Boolean
    fun isConnecting(): Boolean
    fun isHandshakeInProgress(): Boolean
    fun connect(reason: String)
    fun disconnect(reason: String)
    fun stopConnecting()
}''',
    'CommandQueue.kt': '''package app.aaps.core.interfaces.queue
interface CommandQueue {
    var waitingForDisconnect: Boolean
    fun performing(): Command?
    fun size(): Int
    fun bolusInQueue(): Boolean
    fun readStatus(reason: String, callback: Callback?): Boolean
}''',
    'ActivePlugin.kt': 'package app.aaps.core.interfaces.plugin\nimport app.aaps.core.interfaces.pump.Pump\ninterface ActivePlugin { val activePump: Pump }',
    'AAPSLogger.kt': 'package app.aaps.core.interfaces.logging\nenum class LTag { PUMPQUEUE, APS }\ninterface AAPSLogger { fun debug(tag: LTag, message: String) }',
    'ResourceHelper.kt': 'package app.aaps.core.interfaces.resources\ninterface ResourceHelper { fun gs(id: Int, vararg args: Any): String }',
    'DateUtil.kt': 'package app.aaps.core.interfaces.utils\ninterface DateUtil { fun now(): Long; fun dateAndTimeAndSecondsString(timestamp: Long): String; fun dateAndTimeString(timestamp: Long): String }',
    'IntKey.kt': 'package app.aaps.core.keys\nenum class IntKey { ApsMaxSmbFrequency }',
    'Preferences.kt': 'package app.aaps.core.keys.interfaces\nimport app.aaps.core.keys.IntKey\ninterface Preferences { fun get(key: IntKey): Int }',
    'AndroidInjector.kt': 'package dagger.android\nfun interface AndroidInjector<T> { fun inject(instance: T) }\nfun interface HasAndroidInjector { fun androidInjector(): AndroidInjector<Any> }',
    'R.kt': 'package app.aaps.core.ui\nobject R { object string { const val smb_bolus_u = 1; const val format_insulin_units = 2; const val connectiontimedout = 3 } }',
}
for name, content in external_apis.items():
    (SRC / name).write_text(content + '\n')

compiler_cp = str(GRADLE_LIB / '*')
needed = ['kotlin-stdlib-2.2.0.jar', 'kotlin-reflect-2.2.0.jar', 'annotations-24.0.1.jar', 'guava-33.4.6-jre.jar', 'failureaccess-1.0.3.jar']
classpath = ':'.join(str(GRADLE_LIB / jar) for jar in needed) + ':' + ':'.join(str(jar) for jar in sorted((TOOLS / 'jars').glob('*.jar')))
subprocess.run([JAVA, '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', classpath, '-d', str(CLASSES), *[str(path) for path in sorted(SRC.glob('*.kt'))]], check=True)
subprocess.run([JAVA, '-Djdk.attach.allowAttachSelf=true', '-javaagent:' + str(TOOLS / 'jars/byte-buddy-agent-1.17.8.jar'), '-jar', str(TOOLS / 'jars/junit-platform-console-standalone-6.0.1.jar'), 'execute', '--disable-banner', '--class-path', str(CLASSES) + ':' + classpath, '--select-class', 'app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMBTest', '--select-class', 'app.aaps.implementation.queue.commands.CommandSMBBolusTest', '--select-class', 'app.aaps.plugins.main.general.overview.notifications.NotificationDeliveryPolicyTest', '--select-class', 'app.aaps.receivers.PumpConnectionReminderPolicyTest', '--select-class', 'app.aaps.receivers.PumpConnectionRecoveryTest', '--reports-dir', str(TOOLS / 'reports'), '--details', 'tree'], check=True)
