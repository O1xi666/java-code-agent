package com.example.javacodeagent.service;

import com.example.javacodeagent.util.HttpClientUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StockSectorService {

    private static final String SECTOR_URL = "https://push2.eastmoney.com/api/qt/clist/get";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<SectorVO> getSectorList(int pageIndex, int pageSize) {
        try {
            String url = SECTOR_URL + "?pn=" + pageIndex + "&pz=" + pageSize
                    + "&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:90+t:2&fields=f2,f3,f4,f12,f14";
            String response = HttpClientUtil.get(url);
            JsonNode root = MAPPER.readTree(response);
            JsonNode data = root.get("data");
            if (data == null || data.get("diff") == null) return List.of();

            List<SectorVO> sectors = new ArrayList<>();
            for (JsonNode item : data.get("diff")) {
                SectorVO vo = new SectorVO();
                vo.setCode(getStr(item, "f12"));
                vo.setName(getStr(item, "f14"));
                vo.setCurrentPrice(getDbl(item, "f2"));
                vo.setChangePercent(getDbl(item, "f3"));
                sectors.add(vo);
            }
            return sectors;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static class SectorVO {
        private String code, name;
        private Double currentPrice, changePercent;
        public String getCode() { return code; }
        public void setCode(String v) { code = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public Double getCurrentPrice() { return currentPrice; }
        public void setCurrentPrice(Double v) { currentPrice = v; }
        public Double getChangePercent() { return changePercent; }
        public void setChangePercent(Double v) { changePercent = v; }
    }

    private double getDbl(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? 0.0 : v.asDouble();
    }
    private String getStr(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? "" : v.asText();
    }
}