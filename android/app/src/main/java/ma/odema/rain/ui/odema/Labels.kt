package ma.odema.rain.ui.odema

import android.content.Context
import ma.odema.core.ops.AssetKind
import ma.odema.core.ops.IncidentStatus
import ma.odema.core.ops.Priority
import ma.odema.core.ops.ProductCategory
import ma.odema.core.ops.ReadingKind
import ma.odema.core.ops.StaffRole
import ma.odema.core.ops.TeamRole
import ma.odema.core.ops.WorkStatus
import ma.odema.core.ops.WorkType
import ma.odema.rain.R

/** Display names of the management enums, in the app's language. */
object Labels {
    fun work(c: Context, t: WorkType) = c.getString(when (t) {
        WorkType.SOIL -> R.string.wt_soil
        WorkType.SOWING -> R.string.wt_sowing
        WorkType.FERTILIZER -> R.string.wt_fertilizer
        WorkType.SPRAY -> R.string.wt_spray
        WorkType.WEEDING -> R.string.wt_weeding
        WorkType.IRRIGATION -> R.string.wt_irrigation
        WorkType.HARVEST -> R.string.wt_harvest
        WorkType.PRUNING -> R.string.wt_pruning
        WorkType.MAINTENANCE -> R.string.wt_maintenance
        WorkType.TRANSPORT -> R.string.wt_transport
        WorkType.OTHER -> R.string.wt_other
    })

    fun status(c: Context, s: WorkStatus) = c.getString(when (s) {
        WorkStatus.PLANNED -> R.string.ws_planned
        WorkStatus.IN_PROGRESS -> R.string.ws_in_progress
        WorkStatus.DONE -> R.string.ws_done
    })

    fun incident(c: Context, s: IncidentStatus) = c.getString(when (s) {
        IncidentStatus.OPEN -> R.string.is_open
        IncidentStatus.IN_PROGRESS -> R.string.is_in_progress
        IncidentStatus.RESOLVED -> R.string.is_resolved
    })

    fun priority(c: Context, p: Priority) = c.getString(if (p == Priority.URGENT) R.string.pr_urgent else R.string.pr_normal)

    fun asset(c: Context, k: AssetKind) = c.getString(when (k) {
        AssetKind.WELL -> R.string.ak_well
        AssetKind.POWER -> R.string.ak_power
        AssetKind.BASIN -> R.string.ak_basin
        AssetKind.PIVOT -> R.string.ak_pivot
        AssetKind.PUMP -> R.string.ak_pump
        AssetKind.TRACTOR -> R.string.ak_tractor
        AssetKind.VEHICLE -> R.string.ak_vehicle
        AssetKind.EQUIPMENT -> R.string.ak_equipment
        AssetKind.BUILDING -> R.string.ak_building
        AssetKind.OTHER -> R.string.ak_other
    })

    fun reading(c: Context, k: ReadingKind) = c.getString(when (k) {
        ReadingKind.METER_KWH -> R.string.rk_meter
        ReadingKind.FLOW -> R.string.rk_flow
        ReadingKind.LEVEL -> R.string.rk_level
        ReadingKind.PUMP_HOURS -> R.string.rk_hours
        ReadingKind.WATER_M3 -> R.string.rk_water
        ReadingKind.OTHER -> R.string.rk_other
    })

    fun category(c: Context, k: ProductCategory) = c.getString(when (k) {
        ProductCategory.FERTILIZER -> R.string.pc_fertilizer
        ProductCategory.PESTICIDE -> R.string.pc_pesticide
        ProductCategory.SEED -> R.string.pc_seed
        ProductCategory.FUEL -> R.string.pc_fuel
        ProductCategory.PART -> R.string.pc_part
        ProductCategory.IRRIGATION -> R.string.pc_irrigation
        ProductCategory.OTHER -> R.string.pc_other
    })

    fun role(c: Context, r: StaffRole) = c.getString(when (r) {
        StaffRole.MANAGER -> R.string.sr_manager
        StaffRole.FOREMAN -> R.string.sr_foreman
        StaffRole.WORKER -> R.string.sr_worker
        StaffRole.GUARD -> R.string.sr_guard
        StaffRole.DRIVER -> R.string.sr_driver
        StaffRole.TRACTOR_DRIVER -> R.string.sr_tractor_driver
        StaffRole.MECHANIC -> R.string.sr_mechanic
        StaffRole.IRRIGATOR -> R.string.sr_irrigator
        StaffRole.OTHER -> R.string.sr_other
    })

    fun team(c: Context, r: TeamRole) = c.getString(when (r) {
        TeamRole.ADMIN -> R.string.tr_admin
        TeamRole.DIRECTION -> R.string.tr_direction
        TeamRole.CHEF -> R.string.tr_chef
        TeamRole.SAISIE -> R.string.tr_saisie
        TeamRole.LECTURE -> R.string.tr_lecture
        TeamRole.PENDING -> R.string.tr_pending
        TeamRole.BLOCKED -> R.string.tr_blocked
    })
}
