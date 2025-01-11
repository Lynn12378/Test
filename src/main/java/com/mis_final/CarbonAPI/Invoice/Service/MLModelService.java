package com.mis_final.CarbonAPI.Invoice.Service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import com.mycompany.invoice_1114.PredictionAPI;
import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ML模型服務類
 * 負責管理機器學習模型的生命週期，包括初始化、載入和預測
 */
@Slf4j
@Service
public class MLModelService {
    //log & ML調用丟進model - start
    @Value("${ml.model.path}")
    private String modelPath;

    @Value("${ml.labels.path}")
    private String labelsPath;

    private volatile PredictionAPI predictionAPI;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 5000;

    @PostConstruct
    public void init() {
        try {
            // 在初始化前設置 Stanford CoreNLP 的配置
            initializeStanfordNLP();
            
            // 初始載入模型
            loadModel();
            isInitialized.set(true);
            log.info("ML模型初始化成功完成");
            
        } catch (Exception e) {
            log.error("ML模型初始化失敗: {}", e.getMessage(), e);
            logSystemEnvironment();
            handleInitializationFailure(e);
        }
    }

    private void initializeStanfordNLP() {
        try {
            log.info("開始初始化 Stanford CoreNLP 配置");
            
            // 設置中文分詞模型路徑
            System.setProperty("segment.serDictionary", "edu/stanford/nlp/models/segmenter/chinese/dict-chris6.ser.gz");
            System.setProperty("segment.model", "edu/stanford/nlp/models/segmenter/chinese/ctb.gz");
            System.setProperty("stanford.corenlp.properties", "StanfordCoreNLP-chinese.properties");
            
            log.info("Stanford CoreNLP 配置初始化完成");
        } catch (Exception e) {
            log.error("Stanford CoreNLP 初始化失敗: {}", e.getMessage());
            throw new RuntimeException("Stanford CoreNLP initialization failed", e);
        }
    }

    private void logSystemEnvironment() {
        log.error("=== 系統環境信息 ===");
        log.error("作業系統: {}", System.getProperty("os.name"));
        log.error("Java版本: {}", System.getProperty("java.version"));
        log.error("應用程式路徑: {}", System.getProperty("user.dir"));
        log.error("模型配置路徑: {}", modelPath);
        log.error("標籤配置路徑: {}", labelsPath);
    }

    // 添加重新載入方法，使用雙重檢查鎖定模式
    public void reloadModel() {
        synchronized (this) {
            try {
                log.info("開始重新載入模型");
                loadModel();
                log.info("模型重新載入成功");
            } catch (Exception e) {
                log.error("模型重新載入失敗: {}", e.getMessage(), e);
                throw new RuntimeException("Model reload failed", e);
            }
        }
    }

    // 使用同步方法確保線程安全
    private synchronized void loadModel() throws Exception {
        try {
            // 首先嘗試從 ClassPath 資源載入
            Resource modelResource = new ClassPathResource(modelPath);
            Resource labelsResource = new ClassPathResource(labelsPath);
            
            File modelFile;
            File labelsFile;
            
            if (modelResource.exists() && labelsResource.exists()) {
                // 從 ClassPath 載入
                modelFile = File.createTempFile("model", ".tmp");
                labelsFile = File.createTempFile("labels", ".tmp");
                
                try (InputStream modelIn = modelResource.getInputStream();
                     InputStream labelsIn = labelsResource.getInputStream()) {
                    Files.copy(modelIn, modelFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Files.copy(labelsIn, labelsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                // 從外部路徑載入
                String jarPath = System.getProperty("user.dir");
                modelFile = new File(jarPath, modelPath);
                labelsFile = new File(jarPath, labelsPath);
                
                if (!modelFile.exists() || !labelsFile.exists()) {
                    throw new FileNotFoundException("無法找到模型或標籤文件");
                }
            }
            
            log.debug("載入模型檔案: {}", modelFile.getAbsolutePath());
            log.debug("載入標籤檔案: {}", labelsFile.getAbsolutePath());
            
            // 創建新的 PredictionAPI 實例
            PredictionAPI newAPI = new PredictionAPI(
                modelFile.getAbsolutePath(),
                labelsFile.getAbsolutePath()
            );
            
            // 確保新實例創建成功後再替換舊實例
            this.predictionAPI = newAPI;
            
        } catch (Exception e) {
            log.error("載入模型時發生錯誤: {}", e.getMessage());
            throw e;
        }
    }

    private void handleInitializationFailure(Exception e) {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                log.info("嘗試重新初始化模型 (嘗試 {}/{})", attempt, MAX_RETRY_ATTEMPTS);
                Thread.sleep(RETRY_DELAY_MS);
                loadModel();
                isInitialized.set(true);
                return;
            } catch (Exception retryException) {
                log.error("重試失敗 (嘗試 {}/{}): {}", 
                    attempt, MAX_RETRY_ATTEMPTS, retryException.getMessage());
            }
        }
        log.error("所有重試均失敗");
    }

    /**
     * 處理商品描述並進行預測
     * @param description 商品描述
     * @param quantity 數量
     * @return 預測結果
     * @throws Exception 如果預測過程中發生錯誤
     */
public Map<String, Object> processAndPredict(String description, int quantity) throws Exception {
    if (!isInitialized.get()) {
        log.error("ML模型尚未初始化完成");
        throw new RuntimeException("ML model not initialized");
    }
    
    try {
        String path = predictionAPI.processAndPredict(description, quantity);
        
        // 轉換為與 Ollama API 相容的格式
        Map<String, Object> result = new HashMap<>();
        result.put("name", description);
        result.put("path", path);
        result.put("quantity", quantity);
        result.put("status", "SUCCESS");
        
        return result;
    } catch (Exception e) {
        log.error("預測過程發生錯誤 [描述: {}, 數量: {}]: {}", description, quantity, e.getMessage());
        
        // 錯誤時返回統一格式
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("name", description);
        errorResult.put("path", null);
        errorResult.put("quantity", quantity);
        errorResult.put("status", "ERROR");
        errorResult.put("error", e.getMessage());
        
        return errorResult;
    }
}
    
    //log & ML調用丟進model - end
}