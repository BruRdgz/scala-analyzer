package scalaanalyzer.platform

import com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase
import com.intellij.codeInspection.ex.{GlobalInspectionToolWrapper, InspectionProfileImpl, InspectionToolsSupplier}
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.SchemeManager
import com.intellij.profile.codeInspection.BaseInspectionProfileManager
import com.intellij.util.messages.MessageBus

/** Supplies the native entry-point defaults queried by Scala's local unused-symbol analysis, without desktop profiles. */
private[scalaanalyzer] final class HostInspectionProfileManager(bus: MessageBus) extends BaseInspectionProfileManager(bus) with Disposable {
  private val supplier = new InspectionToolsSupplier.Simple(java.util.List.of(
    new GlobalInspectionToolWrapper(new UnusedDeclarationInspectionBase)))
  private lazy val profile: InspectionProfileImpl = new InspectionProfileImpl("Host entry points", supplier, this, null, null) {
    override protected def forceInitInspectionTools(): Boolean = true
  }

  override def dispose(): Unit = {
    profile.cleanup(null)
    supplier.dispose()
  }

  override def getCurrentProfile: InspectionProfileImpl = profile
  override def getProfiles: java.util.Collection[InspectionProfileImpl] = java.util.List.of(profile)
  override def getProfile(name: String, returnRootProfileIfNamedIsAbsent: Boolean): InspectionProfileImpl =
    if (name == profile.getName || returnRootProfileIfNamedIsAbsent) profile else null
  override def setRootProfile(name: String): Unit = CallLog.unsupported("InspectionProfileManager.setRootProfile")
  override def fireProfileChanged(changed: InspectionProfileImpl): Unit = CallLog.unsupported("InspectionProfileManager.fireProfileChanged")
  override protected def getSchemeManager: SchemeManager[InspectionProfileImpl] = CallLog.unsupported("InspectionProfileManager.schemeManager")
}
