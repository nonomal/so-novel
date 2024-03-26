package com.pcdd.sonovel.parse;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.model.SearchResult;
import lombok.SneakyThrows;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * @author pcdd
 */
public class SearchResultParser {

    private final Rule rule;

    public SearchResultParser(int sourceId) {
        // 根据 ruleId 获取对应 json 文件内容，不要使用 FileUtil.readString()！！因为不支持从 JAR 文件中读取文件
        String jsonStr = ResourceUtil.readUtf8Str("rule/rule" + sourceId + ".json");
        // json 封装进 Rule
        this.rule = JSONUtil.toBean(jsonStr, Rule.class);
    }

    @SneakyThrows
    public List<SearchResult> parse(String keyword) {
        Rule.Search search = rule.getSearch();
        Connection connect = Jsoup.connect(search.getUrl());
        // 搜索结果页DOM
        Document document = connect.data("searchkey", keyword).post();
        Elements elements = document.selectXpath(search.getResult());

        List<SearchResult> list = new ArrayList<>();
        for (Element element : elements) {
            // jsoup 不支持一次性获取属性的值
            String url = element.selectXpath(search.getBookName()).attr("href");
            String bookName = element.selectXpath(search.getBookName()).text();
            String latestChapter = element.selectXpath(search.getLatestChapter()).text();
            String author = element.selectXpath(search.getAuthor()).text();
            String update = element.selectXpath(search.getUpdate()).text();

            // 排除第一个 tr（表头）
            // 如果存在任何一个字符串为空字符串，则执行相应的操作
            if (Stream.of(url, bookName, latestChapter, author, update).anyMatch(String::isEmpty)) {
                continue;
            }
            SearchResult build = SearchResult.builder()
                    .url(url)
                    .bookName(bookName)
                    .latestChapter(latestChapter)
                    .author(author)
                    .latestUpdate(update)
                    .build();

            list.add(build);
        }

        return list;
    }


}