package screener.engine;

import screener.model.DisclosureCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 필수 검증 → 상태(관찰/후보 단계/확인 대기/무효화) 판정. 히스테리시스로 잦은 상태 전환을 막는다. */
public final class Classifier {
    private Classifier() { }

    public static final Set<String> MAS_ACTIVE = Set.of("WATCH", "EARLY", "STRONG", "HIGH", "VERY_HIGH", "WEAKENED", "PENDING");
    public static final Set<String> OIS_ACTIVE = Set.of("OBSERVE", "INTEREST", "PRIORITY", "PENDING");

    /** 공시 스캔 결과를 필수 검증 행으로 변환. 반환: CONFIRMED/UNCONFIRMED/REVIEW/BLOCK */
    static String disclosureStatus(ScoreCard c, DisclosureCheck dc) {
        if (dc == null) {
            c.mandatory.add(RiskRules.mandatoryRow("공시 위험 스캔", "UNCONFIRMED", "미실행(가능 점수 범위가 후보 기준 미만이라 생략)", "-"));
            return "UNCONFIRMED";
        }
        if (!dc.ok) {
            c.mandatory.add(RiskRules.mandatoryRow("공시 위험 스캔", "UNCONFIRMED", "수집 실패: " + dc.error, dc.source));
            c.missing.add("공시 스캔 실패 — 필수 검증 미확인");
            return "UNCONFIRMED";
        }
        List<String> blk = new ArrayList<>(), rev = new ArrayList<>();
        for (DisclosureCheck.Event e : dc.events) {
            if (e.level() == DisclosureCheck.Level.BLOCK) blk.add(e.date() + " " + e.title());
            else if (e.level() == DisclosureCheck.Level.REVIEW) rev.add(e.date() + " " + e.title());
        }
        String st = !blk.isEmpty() ? "BLOCK" : !rev.isEmpty() ? "REVIEW" : "CONFIRMED";
        String det = dc.window + " " + dc.scanned + "건 스캔" + (blk.isEmpty() ? "" : "; 차단: " + String.join(" | ", blk))
                + (rev.isEmpty() ? "" : "; 검토 필요: " + String.join(" | ", rev));
        c.mandatory.add(RiskRules.mandatoryRow("공시 위험 스캔", st, det, dc.source));
        for (String r : rev) c.cons.add("위험 공시(규모·영향 확인 필요): " + r);
        for (String r : blk) c.cons.add("중대 위험 공시: " + r);
        return st;
    }

    static String financialStatus(ScoreCard c, RiskRules.FinCheck fc, String src) {
        String det = String.join("; ", fc.notes());
        if (!fc.blocks().isEmpty()) det = (det.isEmpty() ? "" : det + "; ") + "차단: " + String.join(", ", fc.blocks());
        if (!fc.reviews().isEmpty()) det = (det.isEmpty() ? "" : det + "; ") + "검토: " + String.join(", ", fc.reviews());
        c.mandatory.add(RiskRules.mandatoryRow("재무 필수 검증", fc.status(), det.isEmpty() ? "최근 손익·재무상태 확인" : det, src));
        for (String b : fc.blocks()) c.cons.add("재무 중대 위험: " + b);
        for (String r : fc.reviews()) c.cons.add("재무 주의: " + r);
        if (!fc.reviews().isEmpty() && "CONFIRMED".equals(fc.status())) return "REVIEW";
        return fc.status();
    }

    /**
     * MAS 최종 상태. prev=이번 기준일 이전 상태(없으면 null), tracked=추적 중 후보 여부.
     */
    public static void finalizeMas(ScoreCard c, RiskRules.FinCheck fc, DisclosureCheck dc, String prev, boolean tracked, String finSrc) {
        String fin = financialStatus(c, fc, finSrc);
        String dis = disclosureStatus(c, dc);
        c.mandatory.add(0, RiskRules.mandatoryRow("신규 등록 필터", c.filterPassed ? "CONFIRMED" : c.filterUnconfirmed ? "UNCONFIRMED" : "FAIL",
                tracked ? "추적 중 후보 — 가격 필터는 신규 등록에만 적용" : "원본 01 대상·가격 조건", "필터"));
        c.blocked = "BLOCK".equals(fin) || "BLOCK".equals(dis);
        if (c.blocked) c.blockReason = "BLOCK".equals(fin) ? "재무 중대 위험" : "중대 위험 공시";
        c.mandatoryPassed = "CONFIRMED".equals(fin) && "CONFIRMED".equals(dis);
        double S = c.S();
        double cov = c.coverage();
        int va = c.validAreas();
        qualifier(c, true);
        confidenceMas(c);
        boolean wasTracked = tracked && prev != null && !"INVALIDATED".equals(prev) && !"ENDED".equals(prev);
        // 무효화 후 추적 기간(60거래일) 동안은 '초기 매집 의심' 이상(+2점 버퍼)으로 재확인될 때만 복귀 — 반복 알림 방지
        boolean invalidHold = tracked && "INVALIDATED".equals(prev);

        String st;
        String reason;
        if (c.blocked) {
            st = wasTracked || invalidHold ? "INVALIDATED" : "BLOCKED";
            reason = c.blockReason + " — 점수와 무관하게 " + (wasTracked ? "무효화(위험 알림)" : invalidHold ? "무효화 유지" : "신규 등록 차단");
        } else if (!wasTracked && !c.filterPassed) {
            st = invalidHold ? "INVALIDATED" : c.filterUnconfirmed ? "PENDING" : "NONE";
            reason = invalidHold ? "무효화 유지(추적 기간 중, 신규 등록 필터 미충족)"
                    : c.filterUnconfirmed ? "신규 등록 필터 미확정(근사값 경계·자료 지연·주식수 변동)" : "신규 등록 필터 미통과";
        } else if (!wasTracked && !invalidHold && c.rangeHigh() < 50) {
            st = "NONE";
            reason = "가능 점수 범위 최대 " + c.rangeHigh() + " < 50 — 후보 기준 미달(공시 스캔 생략)";
        } else if (cov < 70) {
            st = invalidHold ? "INVALIDATED" : "PENDING";
            reason = "커버리지 " + cov + "% < 70% — 정성 관찰만 제공";
        } else if (!c.mandatoryPassed) {
            st = invalidHold ? "INVALIDATED" : "PENDING";
            List<String> r = new ArrayList<>();
            if (!"CONFIRMED".equals(fin)) r.add("재무 " + fin);
            if (!"CONFIRMED".equals(dis)) r.add("공시 " + dis);
            reason = "필수 재무·공시 검증 미완료(" + String.join(", ", r) + ")";
        } else {
            st = Rules.masStage(S);
            reason = "확인 점수 " + S + " 기준 " + Rules.label(st);
            if (Rules.masRank(st) >= 2 && va < 2) {
                st = "WATCH";
                reason = "점수 " + S + "이나 유효 신호 영역 " + va + "개(<2) — 관찰로 제한, 알림 제외";
            }
            if (wasTracked || invalidHold) {
                String base = invalidHold ? "INVALIDATED" : prev;
                String h = hysteresis(base, st, S);
                if (!h.equals(st)) reason = "히스테리시스: 이전 " + Rules.label(base) + ", 점수 " + S + " → " + Rules.label(h);
                st = h;
            }
            if ("NONE".equals(st) && wasTracked) { st = "WEAKENED"; reason = "점수 " + S + " < 50 — 신호 약화"; }
        }
        c.status = st;
        c.statusLabel = Rules.label(st);
        c.statusReason = reason;
    }

    /**
     * 원본 01 초기 설계: 강화 ≤67 약화 검토, 고득점 ≤77 강화 하향. 같은 원리를 다른 단계에도 3점 하향 버퍼로 적용하고,
     * 추적 중 상향은 단계 하한+2점 이상일 때만 인정한다(app-10).
     */
    public static String hysteresis(String prev, String cand, double S) {
        if (prev == null || "PENDING".equals(prev)) return cand;
        int rp = Rules.masRank(prev), rc = Rules.masRank(cand);
        boolean strongUp = rc >= 2 && S >= Rules.masLower(cand) + 2;
        if ("INVALIDATED".equals(prev)) return strongUp ? cand : "INVALIDATED";
        if ("WEAKENED".equals(prev)) {
            if (strongUp) return cand;
            if (S < 50) return "INVALIDATED";
            return "WEAKENED";
        }
        if (rc > rp) return rp == 0 || S >= Rules.masLower(cand) + 2 ? cand : prev;
        if (rc == rp) return cand;
        if (S > Rules.masLower(prev) - 3) return prev;             // 예: 강화(70) → 68·69 유지, 67 이하 하향
        // 고득점·매우강함에서 하락: 강화 이상이면 해당 단계로, 그 아래면 신호 약화
        if (("HIGH".equals(prev) || "VERY_HIGH".equals(prev)) && rc >= 3) return cand;
        return "WEAKENED";
    }

    static void qualifier(ScoreCard c, boolean mas) {
        double S = c.S(), H = c.rangeHigh();
        if (c.coverage() < 70) c.gradeQualifier = "미산출(커버리지<70%, 정성 관찰)";
        else if (c.W() >= c.total()) c.gradeQualifier = "확정";
        else {
            String a = mas ? Rules.masStage(S) : Rules.oisStage(S), b = mas ? Rules.masStage(H) : Rules.oisStage(H);
            c.gradeQualifier = a.equals(b) ? "잠정(가능 범위 " + S + "~" + H + " 동일 단계)" : "하한 기준(가능 범위 " + S + "~" + H + "가 여러 단계에 걸침)";
        }
    }

    static void confidenceMas(ScoreCard c) {
        var areas = c.areas();
        ScoreCard.Area fl = areas.get(MasScorer.A_FLOW), fin = areas.get(MasScorer.A_FIN);
        boolean flowEval = fl != null && fl.evaluatedMax > 0, flowValid = fl != null && fl.valid();
        boolean finEval = fin != null && fin.evaluatedMax > 0;
        boolean delayed = c.items.stream().anyMatch(i -> i.delayed);
        int va = c.validAreas();
        if (flowEval && flowValid && va >= 3 && c.coverage() >= 90 && !delayed) c.confidence = "A";
        else if (va >= 2 && (flowEval || (finEval && va >= 3)) && !delayed) c.confidence = "B";
        else c.confidence = "C";
        if (!flowEval) c.info.put("confidenceNote", "투자자별 직접 수급 미확인 — 가격·거래량 등 간접 신호 의존(신뢰도 A 불가)");
    }

    public static void finalizeOis(ScoreCard c, RiskRules.FinCheck fc, DisclosureCheck dc, String prev, String finSrc) {
        String fin = financialStatus(c, fc, finSrc);
        String dis = disclosureStatus(c, dc);
        c.filterPassed = true;
        c.blocked = "BLOCK".equals(fin) || "BLOCK".equals(dis);
        if (c.blocked) c.blockReason = "BLOCK".equals(fin) ? "재무 중대 위험" : "중대 위험 공시";
        c.mandatoryPassed = "CONFIRMED".equals(fin) && "CONFIRMED".equals(dis);
        qualifier(c, false);
        double cov = c.coverage();
        boolean delayed = c.items.stream().anyMatch(i -> i.delayed);
        c.confidence = cov >= 90 ? "A" : cov >= 75 ? "B" : "C";
        if (delayed && !"C".equals(c.confidence)) c.confidence = "A".equals(c.confidence) ? "B" : "C";
        double S = c.S();
        String st, reason;
        if (c.blocked) { st = "EXCLUDED_RISK"; reason = c.blockReason + " — 후보 제외, 위험 우선 보고"; }
        else if (c.rangeHigh() < 50 && (prev == null || !OIS_ACTIVE.contains(prev))) { st = "HOLD"; reason = "가능 점수 범위 최대 " + c.rangeHigh() + " < 50 — 투자 보류"; }
        else if (cov < 70) { st = "PENDING"; reason = "커버리지 " + cov + "% < 70% — 정성 관찰만"; }
        else if (!c.mandatoryPassed) { st = "PENDING"; reason = "필수 재무·공시 검증 미완료(재무 " + fin + ", 공시 " + dis + ")"; }
        else {
            st = Rules.oisStage(S);
            reason = "확인 점수 " + S + " 기준 " + Rules.label(st);
            if (prev != null && oisRank(prev) > oisRank(st) && S > oisLower(prev) - 3) {
                reason = "히스테리시스: 이전 " + Rules.label(prev) + " 유지(점수 " + S + ")";
                st = prev;
            } else if (prev != null && oisRank(prev) >= 1 && oisRank(st) > oisRank(prev) && S < oisLower(st) + 2) {
                reason = "히스테리시스: 상향 버퍼(+2점) 미달 — 이전 " + Rules.label(prev) + " 유지(점수 " + S + ")";
                st = prev;
            }
        }
        c.status = st;
        c.statusLabel = Rules.label(st);
        c.statusReason = reason;
    }

    static int oisRank(String s) {
        switch (s == null ? "" : s) { case "PRIORITY": return 3; case "INTEREST": return 2; case "OBSERVE": return 1; default: return 0; }
    }

    static double oisLower(String s) {
        switch (s) { case "PRIORITY": return 80; case "INTEREST": return 65; case "OBSERVE": return 50; default: return 0; }
    }
}
