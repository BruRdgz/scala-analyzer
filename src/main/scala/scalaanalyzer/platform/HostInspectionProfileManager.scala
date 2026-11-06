package scalaanalyzer.platform

import com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase
import com.intellij.codeInspection.ex.{GlobalInspectionToolWrapper, InspectionProfileImpl, InspectionToolsSupplier}
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.SchemeManager
import com.intellij.profile.codeInspection.BaseInspectionProfileManager
import com.intellij.util.messages.MessageBus

/**
 * Supplies the native entry-point defaults queried by Scala's local unused-symbol analysis, without desktop profiles.
 *
 * The manager exposes a single profile containing only the unused-declaration inspection, whichever profile name is
 * requested. Profile switching, change events and the scheme manager are not supported and are recorded by `CallLog`.
 *
 * @param bus project message bus handed to the platform's base manager
 */
private[scalaanalyzer] final class HostInspectionProfileManager(bus: MessageBus)
  extends BaseInspectionProfileManager(bus)
  with Disposable {
  private val supplier = new InspectionToolsSupplier.Simple(java.util.List.of(
    new GlobalInspectionToolWrapper(new UnusedDeclarationInspectionBase)))
  /** The only profile, created on first use; it forces initialization of its inspection tools. */
  private lazy val profile: InspectionProfileImpl =
    new InspectionProfileImpl("Host entry points", supplier, this, null, null) {
      override protected def forceInitInspectionTools(): Boolean = true
    }

  /** Releases the profile's tools and the tools supplier. */
  override def dispose(): Unit = {
    profile.cleanup(null)
    supplier.dispose()
  }

  /**
   * Returns the host's only profile.
   *
   * @return the entry-point profile
   */
  override def getCurrentProfile: InspectionProfileImpl = profile

  /**
   * Lists the profiles the host provides.
   *
   * @return a single-element collection with the entry-point profile
   */
  override def getProfiles: java.util.Collection[InspectionProfileImpl] = java.util.List.of(profile)

  /**
   * Looks up a profile by name.
   *
   * @param name requested profile name
   * @param returnRootProfileIfNamedIsAbsent whether to answer with the root profile when `name` does not match
   * @return the entry-point profile if `name` is its name or `returnRootProfileIfNamedIsAbsent` is `true`;
   *         otherwise `null`
   */
  override def getProfile(name: String, returnRootProfileIfNamedIsAbsent: Boolean): InspectionProfileImpl =
    if (name == profile.getName || returnRootProfileIfNamedIsAbsent) profile else null

  /** Rejects switching the root profile, which the host does not support. */
  override def setRootProfile(name: String): Unit = CallLog.unsupported("InspectionProfileManager.setRootProfile")

  /** Rejects profile-change notifications, which the host does not support. */
  override def fireProfileChanged(changed: InspectionProfileImpl): Unit =
    CallLog.unsupported("InspectionProfileManager.fireProfileChanged")

  /** Rejects access to the scheme manager, which the host does not have. */
  override protected def getSchemeManager: SchemeManager[InspectionProfileImpl] =
    CallLog.unsupported("InspectionProfileManager.schemeManager")
}
