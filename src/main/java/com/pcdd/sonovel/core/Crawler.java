package com.pcdd.sonovel.core;

import cn.hutool.core.date.StopWatch;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileAppender;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.http.HtmlUtil;
import cn.hutool.setting.dialect.Props;
import com.pcdd.sonovel.model.NovelChapter;
import com.pcdd.sonovel.model.SearchResult;
import lombok.SneakyThrows;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * @author pcdd
 * Created at 2021/6/10 17:03
 */
public class Crawler {

    private static String novelDir;
    private static final String INDEX_URL;
    private static final String SEARCH_URL;
    private static final String EXT_NAME;
    private static final String SAVE_PATH;
    private static final int THREADS;
    private static final long MIN_TIME_INTERVAL;
    private static final long MAX_TIME_INTERVAL;

    // 加载配置文件参数
    static {
        Props p = Props.getProp("config.properties", StandardCharsets.UTF_8);
        INDEX_URL = p.getStr("index_url");
        SEARCH_URL = p.getStr("search_url");
        EXT_NAME = p.getStr("extName");
        SAVE_PATH = p.getStr("savePath");
        THREADS = p.getInt("threads");
        MIN_TIME_INTERVAL = p.getLong("min");
        MAX_TIME_INTERVAL = p.getLong("max");
    }

    private Crawler() {
    }

    /**
     * 搜索小说
     *
     * @param keyword 关键字
     * @return 匹配的小说列表
     */
    @SneakyThrows
    public static List<SearchResult> search(String keyword) {
        Console.log("==> 正在搜索...");
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        Connection connect = Jsoup.connect(SEARCH_URL);
        // 搜索结果页DOM
        Document document = connect.data("searchkey", keyword).post();

        // tr:nth-child(n+2)表示获取第2个tr开始获取
        Elements elements = document.select("#checkform > table > tbody > tr:nth-child(n+2)");
        List<SearchResult> list = new ArrayList<>();
        for (Element element : elements) {
            SearchResult searchResult = SearchResult.builder()
                    .url(element.child(0).select("a").attr("href"))
                    .bookName(element.child(0).text())
                    .latestChapter(element.child(1).text())
                    .author(element.child(2).text())
                    .latestUpdate(element.child(3).text())
                    .build();
            list.add(searchResult);
        }

        stopWatch.stop();
        Console.log("<== 搜索到 {} 条记录，耗时 {} s\n",
                elements.size(),
                NumberUtil.round(stopWatch.getTotalTimeSeconds(), 2)
        );

        return list;
    }

    /**
     * 爬取小说
     *
     * @param list  搜索到的小说列表
     * @param num   下载序号
     * @param start 从第几章下载
     * @param end   下载到第几章
     */
    @SneakyThrows
    public static double crawl(List<SearchResult> list, int num, int start, int end) {
        SearchResult r = list.get(num);
        String bookName = r.getBookName();
        String author = r.getAuthor();
        // 小说详情页url
        String url = r.getUrl();

        // 小说目录名格式：书名(作者)
        novelDir = String.format("%s（%s）", bookName, author);
        File dir = new File(SAVE_PATH + File.separator + novelDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        Document document = Jsoup.parse(new URL(url), 10000);
        // 获取小说目录
        Elements elements = document.getElementById("list").getElementsByTag("a");
        Console.log("==> 开始下载：《{}》（{}）", bookName, author);

        // 线程池
        ExecutorService executor = Executors.newFixedThreadPool(THREADS == -1
                ? Runtime.getRuntime().availableProcessors() * 2
                : THREADS);
        // 阻塞主线程，用于计时
        CountDownLatch countDownLatch = new CountDownLatch(end == Integer.MAX_VALUE ? elements.size() : end);

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        // elements.size()是小说的总章数
        for (int i = start - 1; i < end && i < elements.size(); i++) {
            int finalI = i;
            executor.execute(() -> {
                download(
                        Objects.requireNonNull(crawlChapter(NovelChapter.builder()
                                .chapterNo(finalI + 1)
                                .title(elements.get(finalI).text())
                                .url(INDEX_URL + elements.get(finalI).attr("href"))
                                .build(), countDownLatch)),
                        countDownLatch
                );
                countDownLatch.countDown();
            });
        }
        countDownLatch.await();

        if ("txt".equals(EXT_NAME)) {
            Console.log("<== 下载完成，开始合并 txt");
            mergeTxt(bookName, dir);
        }

        stopWatch.stop();
        return stopWatch.getTotalTimeSeconds();
    }

    private static void mergeTxt(String bookName, File dir) {
        File file = FileUtil.touch(System.getProperty("user.dir") + File.separator + SAVE_PATH + File.separator + bookName + ".txt");
        FileAppender appender = new FileAppender(file, 16, true);
        // 文件排序
        List<File> files = Arrays.stream(dir.listFiles())
                .sorted((o1, o2) -> {
                    String s1 = o1.getName();
                    String s2 = o2.getName();
                    int no1 = Integer.parseInt(s1.substring(0, s1.indexOf("_")));
                    int no2 = Integer.parseInt(s2.substring(0, s2.indexOf("_")));
                    return no1 - no2;
                })
                .toList();
        for (File item : files) {
            String s = FileUtil.readString(item, StandardCharsets.UTF_8);
            appender.append(s);
        }
        appender.flush();
    }

    /**
     * 爬取小说章节
     */
    private static NovelChapter crawlChapter(NovelChapter novelChapter, CountDownLatch latch) {
        try {
            // 设置时间间隔
            long timeInterval = ThreadLocalRandom.current().nextLong(MIN_TIME_INTERVAL, MAX_TIME_INTERVAL);
            TimeUnit.MILLISECONDS.sleep(timeInterval);
            Console.log("正在下载：【{}】 间隔 {} ms", novelChapter.getTitle(), timeInterval);
            Document document = Jsoup.parse(new URL(novelChapter.getUrl()), 10000);
            String content = document.getElementById("content").html();

            // txt 格式
            if ("txt".equals(EXT_NAME)) {
                content = novelChapter.getTitle() + HtmlUtil.cleanHtmlTag(content)
                        .replace("&nbsp;", " ")
                        // 去除其它内容
                        .replace("最新网址：www.xbiqugu.info", "")
                        .replace("(www.xbiquge.la 新笔趣阁)，高速全文字在线阅读！", "")
                        .replace("亲,点击进去,给个好评呗,分数越高更新越快,据说给香书小说打满分的最后都找到了漂亮的老婆哦!", "")
                        .replace("手机站全新改版升级地址：https://wap.xbiqugu.info，数据和书签与电脑站同步，无广告清新阅读！", "");
            }
            novelChapter.setContent(content);

            return novelChapter;
        } catch (Exception e) {
            latch.countDown();
            Console.error(e, e.getMessage());
        }
        return null;
    }

    /**
     * 下载到本地
     */
    private static void download(NovelChapter novelChapter, CountDownLatch latch) {
        // Windows 文件名非法字符替换
        String path = SAVE_PATH + File.separator + novelDir + File.separator
                + novelChapter.getChapterNo()
                + "_" + novelChapter.getTitle().replaceAll("\\\\|/|:|\\*|\\?|<|>", "")
                + "." + EXT_NAME;
        // TODO fix 下载过快时报错：Exception in thread "pool-2-thread-10" java.io.FileNotFoundException: \so-novel-download\史上最强炼气期（李道然）\3141_第三千一百三十二章 万劫不复 为无敌妙妙琪的两顶皇冠加更（2\2）.html (系统找不到指定的路径。)
        // TODO 改为批量保存
        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(path))) {
            fos.write(novelChapter.getContent().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            latch.countDown();
            Console.error(e, e.getMessage());
        }
    }

}
