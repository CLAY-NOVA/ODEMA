package ma.odema.rain

import ma.odema.core.ops.Asset
import ma.odema.core.ops.AssetKind
import ma.odema.core.ops.Attendance
import ma.odema.core.ops.FarmPlan
import ma.odema.core.ops.Harvest
import ma.odema.core.ops.Incident
import ma.odema.core.ops.IncidentStatus
import ma.odema.core.ops.InputUse
import ma.odema.core.ops.OpsData
import ma.odema.core.ops.PlanBlock
import ma.odema.core.ops.PlanLine
import ma.odema.core.ops.Priority
import ma.odema.core.ops.Product
import ma.odema.core.ops.ProductCategory
import ma.odema.core.ops.Reading
import ma.odema.core.ops.ReadingKind
import ma.odema.core.ops.Staff
import ma.odema.core.ops.StaffRole
import ma.odema.core.ops.StockMove
import ma.odema.core.ops.Work
import ma.odema.core.ops.WorkStatus
import ma.odema.core.ops.WorkType
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin

/** A made-up farm plan and a week of management records, for screenshots and tests. */
object OpsFakes {
    private val today = LocalDate.now().toEpochDay()
    private val nowMs = System.currentTimeMillis()

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(x0, y0, x1, y0, x1, y1, x0, y1)
    private fun circle(cx: Double, cy: Double, r: Double) = (0..48).flatMap { i -> val a = 2 * Math.PI * i / 48; listOf(cx + r * cos(a), cy + r * sin(a)) }

    val plan = FarmPlan(
        width = 400.0, height = 260.0, northDeg = -50.0, metersPerUnit = 6.0,
        blocks = listOf(
            PlanBlock("Nord 1", "p-nord", 17.0, listOf(rect(10.0, 10.0, 110.0, 120.0))),
            PlanBlock("Nord 2", "p-nord", 20.0, listOf(rect(110.0, 10.0, 220.0, 120.0))),
            PlanBlock("Sud 1", "p-sud", 22.0, listOf(rect(10.0, 140.0, 140.0, 250.0))),
            PlanBlock("Sud 2", "p-sud", 39.0, listOf(rect(140.0, 140.0, 260.0, 250.0))),
            PlanBlock("Est", "p-est", 30.0, listOf(listOf(240.0, 10.0, 390.0, 10.0, 390.0, 120.0, 300.0, 120.0, 240.0, 60.0))),
            PlanBlock("Pivot 3", "p-pivot", 30.0, listOf(circle(330.0, 195.0, 55.0))),
        ),
        lines = listOf(PlanLine(1.44, listOf(110.0, 10.0, 110.0, 120.0)), PlanLine(0.72, circle(330.0, 195.0, 35.0)), PlanLine(0.72, circle(330.0, 195.0, 15.0))),
        water = listOf(rect(228.0, 10.0, 234.0, 130.0) + listOf()),
        assets = emptyList(),
    )

    val assets = listOf(
        Asset("well-f1", AssetKind.WELL.name, "F1", "F1", planX = 60.0, planY = 130.0, flowM3h = 45.0, depthM = 120.0),
        Asset("well-f2", AssetKind.WELL.name, "F2", "F2", planX = 200.0, planY = 130.0, flowM3h = 38.0),
        Asset("well-f3", AssetKind.WELL.name, "F3", "F3", active = false, planX = 380.0, planY = 130.0),
        Asset("poste-1", AssetKind.POWER.name, "Poste 1", "1", planX = 75.0, planY = 130.0, contract = "1000001", powerKva = 160.0),
        Asset("poste-2", AssetKind.POWER.name, "Poste 2", "2", planX = 270.0, planY = 135.0, contract = "1000002", powerKva = 400.0),
        Asset("basin-1", AssetKind.BASIN.name, "Bassin Nord", planX = 90.0, planY = 130.0, capacityM3 = 5000.0),
        Asset("pivot-3", AssetKind.PIVOT.name, "Pivot 3", planX = 330.0, planY = 195.0, radiusM = 330.0),
        Asset("tractor-1", AssetKind.TRACTOR.name, "John Deere 6120M", "T1"),
    )

    val products = listOf(
        Product("urea", "Urée 46 %", ProductCategory.FERTILIZER.name, "kg", minStock = 1000.0),
        Product("map", "MAP 11-52", ProductCategory.FERTILIZER.name, "kg", minStock = 500.0),
        Product("herb", "Herbicide betterave", ProductCategory.PESTICIDE.name, "L", minStock = 20.0),
        Product("gasoil", "Gasoil", ProductCategory.FUEL.name, "L", minStock = 300.0),
        Product("seed-bt", "Semence BT Faiza G3", ProductCategory.SEED.name, "q"),
    )

    val staff = listOf(
        Staff("s1", "Hassan Alaoui", StaffRole.FOREMAN.name, "0661000001", 150.0),
        Staff("s2", "Omar Benali", StaffRole.TRACTOR_DRIVER.name, "0661000002", 120.0),
        Staff("s3", "Brahim", StaffRole.IRRIGATOR.name, "", 100.0),
        Staff("s4", "Mustapha", StaffRole.GUARD.name, "0661000004", 90.0),
        Staff("s5", "Abdellah", StaffRole.GUARD.name, "", 90.0),
        Staff("s6", "Youssef", StaffRole.WORKER.name, "", 90.0, active = false),
    )

    val data = OpsData(
        assets = assets,
        products = products,
        staff = staff,
        moves = listOf(
            StockMove("m1", "urea", today - 20, 5000.0, unitPrice = 5.6, partner = "Agro-fournitures", updatedAt = nowMs - 86_400_000L * 20, updatedBy = "Hassan"),
            StockMove("m2", "map", today - 20, 1200.0, unitPrice = 7.9, partner = "Agro-fournitures"),
            StockMove("m3", "herb", today - 9, 40.0, unitPrice = 310.0),
            StockMove("m4", "gasoil", today - 3, 600.0, unitPrice = 11.2, partner = "Station-service", updatedAt = nowMs - 3 * 86_400_000L, updatedBy = "Sara"),
            StockMove("w1-urea", "urea", today - 2, -2600.0, workId = "w1", parcelId = "p-sud"),
            StockMove("w1-map", "map", today - 2, -800.0, workId = "w1", parcelId = "p-sud"),
            StockMove("w3-herb", "herb", today - 1, -24.0, workId = "w3", parcelId = "p-nord"),
        ),
        works = listOf(
            Work("w1", WorkType.FERTILIZER.name, WorkStatus.DONE.name, today - 2, parcelId = "p-sud", block = "Sud 2", title = "Engrais de fond",
                inputs = listOf(InputUse("urea", 2600.0), InputUse("map", 800.0)), workers = 4, hours = 7.0, machine = "JD 6120M + épandeur", responsible = "Hassan",
                updatedAt = nowMs - 2 * 86_400_000L, updatedBy = "Hassan"),
            Work("w2", WorkType.SOWING.name, WorkStatus.PLANNED.name, today - 3, parcelId = "p-nord", block = "Nord 2", title = "Semis blé Faiza", responsible = "Omar",
                updatedAt = nowMs - 5 * 86_400_000L, updatedBy = "Sara"),
            Work("w3", WorkType.SPRAY.name, WorkStatus.DONE.name, today - 1, parcelId = "p-nord", block = "Nord 1", title = "Désherbage chimique",
                inputs = listOf(InputUse("herb", 24.0)), workers = 2, hours = 5.0, updatedAt = nowMs - 86_400_000L, updatedBy = "Omar"),
            Work("w4", WorkType.IRRIGATION.name, WorkStatus.IN_PROGRESS.name, today, parcelId = "p-pivot", title = "Tour d'eau pivot", responsible = "Brahim",
                updatedAt = nowMs - 3_600_000L, updatedBy = "Brahim"),
            Work("w5", WorkType.SOIL.name, WorkStatus.PLANNED.name, today + 3, parcelId = "p-est", title = "Sous-solage", machine = "JD 6120M"),
            Work("w6", WorkType.MAINTENANCE.name, WorkStatus.PLANNED.name, today + 5, title = "Vidange tracteur", responsible = "Omar"),
        ),
        incidents = listOf(
            Incident("i1", "Pompe arrêtée", assetId = "well-f2", parcelId = "p-nord", description = "Disjoncteur déclenché, moteur chaud", priority = Priority.URGENT.name,
                reportedAt = nowMs - 2 * 3_600_000L, reportedBy = "Brahim", assignedTo = "Électricien", updatedAt = nowMs - 2 * 3_600_000L, updatedBy = "Brahim"),
            Incident("i2", "Fuite sur la conduite principale", assetId = "pivot-3", status = IncidentStatus.IN_PROGRESS.name,
                reportedAt = nowMs - 30 * 3_600_000L, reportedBy = "Hassan", updatedAt = nowMs - 20 * 3_600_000L, updatedBy = "Hassan"),
            Incident("i3", "Coupure de courant", assetId = "poste-1", status = IncidentStatus.RESOLVED.name, reportedAt = nowMs - 5 * 86_400_000L,
                reportedBy = "Mustapha", resolvedAt = nowMs - 4 * 86_400_000L, resolution = "Fusible MT remplacé par l'ONEE", cost = 0.0),
        ),
        readings = listOf(
            Reading("r1", "poste-2", today - 30, ReadingKind.METER_KWH.name, 182_400.0),
            Reading("r2", "poste-2", today - 15, ReadingKind.METER_KWH.name, 191_150.0),
            Reading("r3", "poste-2", today - 1, ReadingKind.METER_KWH.name, 199_020.0, updatedAt = nowMs - 86_400_000L, updatedBy = "Brahim"),
            Reading("r4", "well-f1", today - 4, ReadingKind.FLOW.name, 44.0),
        ),
        attendance = listOf(
            Attendance(Attendance.idFor(today - 1), today - 1, listOf("s1", "s2", "s3", "s4"), casuals = 8, casualRate = 90.0, guardDay = "s4", guardNight = "s5", updatedAt = nowMs - 86_400_000L, updatedBy = "Hassan"),
            Attendance(Attendance.idFor(today), today, listOf("s1", "s2", "s3", "s5"), casuals = 12, casualRate = 90.0, guardDay = "s5", guardNight = "s4", updatedAt = nowMs - 600_000L, updatedBy = "Hassan"),
        ),
        harvests = listOf(
            Harvest("h1", "p-nord", "Nord 1", FarmFakes.campaign - 1, "BLE_TENDRE", today - 150, 52.0, "13 % H", "Coopérative", 4300.0),
            Harvest("h2", "p-nord", "Nord 1", FarmFakes.campaign - 1, "BLE_TENDRE", today - 148, 40.0, "", "Coopérative", 4300.0),
        ),
    )
}
