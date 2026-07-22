package com.example.javacodeagent.util;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeDriverService;
import org.openqa.selenium.edge.EdgeOptions;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Selenium EdgeDriver 管理工具
 * 使用 Service 模式管理驱动，每个操作加 sleep 模仿真人避免反爬虫
 */
@Service
public class SeleniumDriver {

    private static final Logger log = LoggerFactory.getLogger(SeleniumDriver.class);
    private static final String DRIVER_NAME = "msedgedriver.exe";

    private volatile boolean running = true;
    private volatile boolean healthy = false;
    private WebDriver driver;

    public SeleniumDriver() {}

    @PostConstruct
    public void init() {
        initDriver();
    }

    private void initDriver() {
        try {
            String driverPath = findLocalDriver();

            EdgeOptions options = new EdgeOptions();
            options.addArguments(
                "--headless",
                "--disable-gpu",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-blink-features=AutomationControlled",
                "--window-size=1920,1080",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"
            );
            // 隐藏 webdriver 特征，避免被网站识别
            options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
            options.setExperimentalOption("useAutomationExtension", false);

            if (driverPath != null) {
                // 使用 Service 模式显式指定 EdgeDriver 路径
                EdgeDriverService service = new EdgeDriverService.Builder()
                        .usingDriverExecutable(new File(driverPath))
                        .build();
                driver = new EdgeDriver(service, options);
                log.info("使用 Service 模式加载本地 EdgeDriver: {}", driverPath);
            } else {
                log.info("未找到本地 EdgeDriver，使用 WebDriverManager 自动管理");
                WebDriverManager.edgedriver().setup();
                driver = new EdgeDriver(options);
            }

            // 最大化窗口 + 随机停顿，模仿真人
            driver.manage().window().maximize();
            sleepRandom(1500L, 3000L);

            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(6));
            this.healthy = true;
            log.info("EdgeDriver 初始化成功");
        } catch (Exception e) {
            this.healthy = false;
            log.error("EdgeDriver 初始化失败: {}", e.getMessage(), e);
            throw new RuntimeException("EdgeDriver 初始化失败，请确保 Edge 浏览器已安装", e);
        }
    }

    /**
     * 查找本地的 msedgedriver.exe
     */
    private String findLocalDriver() {
        // 优先级1：user.dir（项目运行目录）
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            Path p = Paths.get(userDir, DRIVER_NAME);
            if (Files.exists(p)) return p.toAbsolutePath().toString();
        }
        // 优先级2：classpath 根目录（部署 jar 包时）
        try {
            String classpath = SeleniumDriver.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            if (classpath != null) {
                Path p = Paths.get(new File(classpath).getParent(), DRIVER_NAME);
                if (Files.exists(p)) return p.toAbsolutePath().toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 导航到指定 URL，每个操作后都 sleep 模仿真人
     */
    public void navigate(String url) {
        if (!running || driver == null) return;
        try {
            log.info("Selenium 导航到: {}", url);
            driver.get(url);
            // 等页面完全加载
            sleepRandom(2000L, 4000L);
            // 模拟鼠标滚动
            slowScroll();
        } catch (Exception e) {
            log.error("导航失败: {}", e.getMessage());
            throw new RuntimeException("导航到 " + url + " 失败: " + e.getMessage());
        }
    }

    /**
     * 获取页面源码，取之前停顿模拟真人阅读
     */
    public String getPageSource() {
        if (!running || driver == null) return "";
        sleepRandom(500L, 1500L);
        return driver.getPageSource();
    }

    /**
     * 导航 + 获取渲染后页面，一步到位
     */
    public String getContent(String url) {
        navigate(url);
        sleepRandom(1000L, 2000L);
        slowScroll();
        sleepRandom(800L, 1500L);
        return getPageSource();
    }

    /**
     * 导航 + 等指定元素出现 + 获取页面内容
     */
    public String getContentAfterWaiting(String url, String cssSelector, int waitSeconds) {
        navigate(url);
        try {
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(waitSeconds));
            WebElement el = driver.findElement(By.cssSelector(cssSelector));
            // 鼠标悬停到目标元素
            new Actions(driver).moveToElement(el).perform();
            sleepRandom(1000L, 2000L);
        } catch (Exception e) {
            log.warn("等待元素 {} 超时: {}", cssSelector, e.getMessage());
        }
        slowScroll();
        sleepRandom(500L, 1500L);
        return getPageSource();
    }

    /**
     * 模拟真人随机停顿，[min, max] 毫秒
     */
    private void sleepRandom(long min, long max) {
        if (!running) return;
        try {
            long delay = min + (long) (Math.random() * (max - min));
            Thread.sleep(delay);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 模拟在页面上缓慢滚动
     */
    private void slowScroll() {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            for (int i = 0; i < 3; i++) {
                js.executeScript("window.scrollBy(0, " + (200 + i * 100) + ")");
                sleepRandom(800L, 1500L);
            }
            // 回顶部
            js.executeScript("window.scrollTo(0, 0)");
            sleepRandom(500L, 1000L);
        } catch (Exception ignored) {}
    }

    /**
     * 最小化并重新最大化窗口，模仿真人操作
     */
    public void wiggleWindow() {
        try {
            driver.manage().window().minimize();
            sleepRandom(1000L, 2000L);
            driver.manage().window().maximize();
            sleepRandom(1000L, 2000L);
        } catch (Exception ignored) {}
    }


    /**
     * 健康检查
     */
    public synchronized boolean isHealthy() {
        if (!running || driver == null) {
            healthy = false;
            return false;
        }
        try {
            driver.getTitle();
            healthy = true;
            return true;
        } catch (Exception e) {
            log.warn("Driver 健康检查失败: {}", e.getMessage());
            healthy = false;
            return false;
        }
    }

    /**
     * 重启浏览器
     */
    public synchronized void restart() {
        log.info("正在重启 EdgeDriver...");
        healthy = false;
        running = false;
        healthy = false;
        if (driver != null) {
            try {
                sleepRandom(500L, 1000L);
                driver.quit();
            } catch (Exception e) {
                log.warn("关闭旧 driver 异常: {}", e.getMessage());
            }
        }
        running = true;
        initDriver();
        log.info("EdgeDriver 重启完成");
    }

    /**
     * 获取原始 WebDriver 实例（仅供内部使用）
     */
    public WebDriver getDriver() {
        return driver;
    }

    @PreDestroy
    public void quit() {
        running = false;
        healthy = false;
        if (driver != null) {
            try {
                // 关闭前停顿，模仿真人关闭浏览器
                sleepRandom(500L, 1500L);
                driver.quit();
                log.info("EdgeDriver 已关闭");
            } catch (Exception e) {
                log.warn("EdgeDriver 关闭异常: {}", e.getMessage());
            }
        }
    }
}
