package br.com.forza.models.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Schema do resumo de tuning publicado no OpenAPI. O endpoint continua devolvendo o mapa
 * produzido por {@code SummaryCalculator} (e o JSON gravado em {@code sessions.summary});
 * este record só <b>descreve</b> esse formato pro contrato. {@code TuningSummaryDTOTest}
 * garante que os dois não divergem. Sessões sem amostras trazem só {@code samples} e
 * {@code durationS}. Rodas nas chaves {@code FL, FR, RL, RR}; temperaturas em °F.
 */
@Schema(description = "Resumo de tuning de uma sessão")
public record TuningSummaryDTO(int samples,
                               double durationS,
                               Map<String, SuspensionWheel> suspension,
                               SpeedKmh speedKmh,
                               Engine engine,
                               Map<String, TireWheel> tires,
                               CornerBalance cornerBalance,
                               Braking braking,
                               Traction traction,
                               Laps laps,
                               Double onRumbleStripPct) {

    public record SuspensionWheel(double mean, double p95, double bottomingPct, double toppingPct) {
    }

    public record SpeedKmh(double max, double meanMoving) {
    }

    public record Engine(double maxRpm,
                         double peakPowerHp,
                         double peakPowerRpm,
                         double peakTorqueNm,
                         double peakTorqueRpm,
                         double boostMaxPsi,
                         Map<String, Gear> gears) {
    }

    public record Gear(double timePct, double rpmP50, double limiterWithThrottlePct) {
    }

    public record TireWheel(double tempMeanF, double tempP95F, Double wearFinal) {
    }

    public record CornerBalance(CornerPhase entry, CornerPhase mid, CornerPhase exit, String note) {
    }

    /** Fase sem amostras traz só {@code samples = 0}. */
    public record CornerPhase(int samples,
                              Double frontSlipAngleMean,
                              Double rearSlipAngleMean,
                              Double understeerPct,
                              Double oversteerPct) {
    }

    public record Braking(int samples, double frontLockPct, double rearLockPct) {
    }

    public record Traction(int samples, double drivenWheelSpinPct, Map<String, Double> spinPctByGear) {
    }

    public record Laps(List<Lap> times, Double bestS) {
    }

    public record Lap(int lap, double timeS) {
    }
}
