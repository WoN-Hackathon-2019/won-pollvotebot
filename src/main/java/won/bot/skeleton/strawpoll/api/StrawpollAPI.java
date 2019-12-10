package won.bot.skeleton.strawpoll.api;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import won.bot.skeleton.strawpoll.api.models.SPPoll;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StrawpollAPI {

    public static Long create(String question, List<String> answers) {
        CloseableHttpClient client = HttpClientBuilder.create().build();
        HttpPost httpPost = new HttpPost("https://www.strawpoll.me/api/v2/polls");

        try {
            JSONObject obj = new JSONObject();
            obj.put("title", question);
            obj.put("options", answers.stream().map(value -> "\"" + value + "\"").toArray());
            obj.put("multi", false);
            String json = obj.toString();

            StringEntity entity = new StringEntity(json);
            httpPost.setEntity(entity);

            // set your POST request headers to accept json contents
            httpPost.setHeader("Accept", "application/json");
            httpPost.setHeader("Content-type", "application/json");

            try {
                CloseableHttpResponse response = client.execute(httpPost);
                String responseJSON = EntityUtils.toString(response.getEntity());

                JSONParser parser = new JSONParser();
                JSONObject jsonObject = (JSONObject) parser.parse(responseJSON);

                return (Long) jsonObject.get("id");
            } catch (IOException | ParseException e) {
                e.printStackTrace();
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static SPPoll getResults(Long id) {
        CloseableHttpClient client = HttpClientBuilder.create().build();
        HttpGet httpGet = new HttpGet("https://www.strawpoll.me/api/v2/polls/" + id);

        // set your POST request headers to accept json contents
        httpGet.setHeader("Accept", "application/json");
        httpGet.setHeader("Content-type", "application/json");

        try {
            CloseableHttpResponse response = client.execute(httpGet);
            String responseJSON = EntityUtils.toString(response.getEntity());

            JSONParser parser = new JSONParser();
            JSONObject jsonObject = (JSONObject) parser.parse(responseJSON);

            return new SPPoll(jsonObject);
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void vote(long id, int index) {
        try {
            SPAuth auth = getVoteAuth(id);
            CloseableHttpClient client = HttpClientBuilder.create().build();
            HttpPost httpPost = new HttpPost("https://www.strawpoll.me/" + id);

            StringEntity entity = new StringEntity("security-token=" + auth.getSecurityToken()
                    + "&" + auth.getFieldAuthenticityToken() + "="
                    + "&options=" + auth.getOptions().get(index));
            httpPost.setEntity(entity);

            httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");

            CloseableHttpResponse response = client.execute(httpPost);
            System.out.println(response.getStatusLine());
            String responseRaw = EntityUtils.toString(response.getEntity());
            System.out.println(responseRaw);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static SPAuth getVoteAuth(long id) throws IOException {
        CloseableHttpClient client = HttpClientBuilder.create().build();
        HttpGet httpGet = new HttpGet("https://www.strawpoll.me/" + id);

        CloseableHttpResponse response = client.execute(httpGet);
        String responseHTML = EntityUtils.toString(response.getEntity());

        Pattern SECURITY_TOKEN_PATTERN = Pattern.compile("\\w+=\"security-token\".*value=\"(\\w+)\"", Pattern.CASE_INSENSITIVE);
        Pattern FIELD_AUTHENTICITY_TOKEN_PATTERN = Pattern.compile("\\w+=\"field-authenticity-token\".*name=\"(\\w+)\"");
        Pattern OPTIONS_PATTERN = Pattern.compile("\\w+=\"options\"\\s*value=\"(\\d+)\"");

        Matcher secTokMatcher = SECURITY_TOKEN_PATTERN.matcher(responseHTML);
        if (!secTokMatcher.find()) {
            throw new RuntimeException("Failed!");
        }
        String securityAuthToken = secTokMatcher.group(1);

        Matcher fieldAuthTokenMatcher = FIELD_AUTHENTICITY_TOKEN_PATTERN.matcher(responseHTML);
        if (!fieldAuthTokenMatcher.find()) {
            throw new RuntimeException("Failed!");
        }
        String fieldAuthenticityToken = fieldAuthTokenMatcher.group(1);

        List<Long> options = new ArrayList<>();
        Matcher optionsMatcher = OPTIONS_PATTERN.matcher(responseHTML);
        while (optionsMatcher.find()) {
            options.add(Long.valueOf(optionsMatcher.group(1)));
        }
        return new SPAuth(securityAuthToken, fieldAuthenticityToken, options);
    }
}

class SPAuth {

    private String securityToken;
    private String fieldAuthenticityToken;
    private List<Long> options;

    public SPAuth(String securityToken, String fieldAuthenticityToken, List<Long> options) {
        this.securityToken = securityToken;
        this.fieldAuthenticityToken = fieldAuthenticityToken;
        this.options = options;
    }

    public String getSecurityToken() {
        return securityToken;
    }

    public String getFieldAuthenticityToken() {
        return fieldAuthenticityToken;
    }

    public List<Long> getOptions() {
        return options;
    }
}