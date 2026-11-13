package screener.engine;

import screener.model.Bar;
import screener.model.FlowDay;
import screener.model.Fundamentals;
import screener.model.Security;
import screener.provider.MarketDataProvider;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 한 시장·한 기준일의 점수 계산 입력. 모든 시계열은 asOf 이하로 잘려 있다(미래 자료 차단). */
public final class MarketContext {
    public final String market;
    public final LocalDate asOf;
    public final MarketDataProvider provider;
    public final Map<String, Security> secs = new HashMap<>();
    public final Map<String, List<Bar>> bars = new HashMap<>();
    public final Map<String, List<Bar>> index = new HashMap<>();
    public final Map<String, List<FlowDay>> flows = new HashMap<>();
    public final Map<String, Fundamentals> fundamentals = new HashMap<>();
    public final Map<String, List<PeerStat>> peers = new HashMap<>();
    public Double msiScore;
    public Double msiCh5;

    public record PeerStat(String symbol, Double r5, Double r20, Double r60, Double pe) { }

    public MarketContext(String market, LocalDate asOf, MarketDataProvider provider) {
        this.market = market;
        this.asOf = asOf;
        this.provider = provider;
    }

    public List<Bar> bench(Security s) {
        List<Bar> b = index.get(provider.benchmarkKey(s));
        return b == null ? List.of() : b;
    }

    /** 업종 동일 종목 통계 구성(자기 자신 제외는 조회 시 처리). */
    public void buildPeers() {
        peers.clear();
        for (Security s : secs.values()) {
            if (s.sector == null) continue;
            List<Bar> b = bars.get(s.symbol);
            if (b == null || b.isEmpty()) continue;
            Fundamentals f = fundamentals.get(s.symbol);
            Double pe = null;
            if (f != null && f.annualNetIncome != null && f.annualNetIncome > 0 && s.marketCap != null) pe = s.marketCap / f.annualNetIncome;
            peers.computeIfAbsent(s.sector, k -> new ArrayList<>())
                    .add(new PeerStat(s.symbol, Indicators.ret(b, 5), Indicators.ret(b, 20), Indicators.ret(b, 60), pe));
        }
    }

    public List<PeerStat> peersOf(Security s) {
        List<PeerStat> out = new ArrayList<>();
        if (s.sector == null) return out;
        for (PeerStat p : peers.getOrDefault(s.sector, List.of())) if (!p.symbol().equals(s.symbol)) out.add(p);
        return out;
    }
}
