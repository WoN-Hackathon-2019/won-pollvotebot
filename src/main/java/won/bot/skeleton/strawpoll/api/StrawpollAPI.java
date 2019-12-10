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
import won.bot.skeleton.strawpoll.api.models.SPAuth;
import won.bot.skeleton.strawpoll.api.models.SPPoll;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StrawpollAPI {

    private static final String STRAWPOLL_HOST = "https://www.strawpoll.me/";
    private static final String STRAW_POLL_API = STRAWPOLL_HOST + "api/v2/polls";

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CONTENT_TYPE_FORM_URLENCODED = "application/x-www-form-urlencoded; charset=utf-8";

    private static final Pattern SECURITY_TOKEN_PATTERN = Pattern.compile("\\w+=\"security-token\".*value=\"(\\w+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_AUTHENTICITY_TOKEN_PATTERN = Pattern.compile("\\w+=\"field-authenticity-token\".*name=\"(\\w+)\"");
    private static final Pattern OPTIONS_PATTERN = Pattern.compile("\\w+=\"options\"\\s*value=\"(\\d+)\"");

    /**
     * Creates a new Strawpoll poll with the given question and answers
     *
     * @param question title of poll
     * @param answers  options to vote for
     * @return id of newly created Strawpoll poll
     */
    public static Long create(String question, List<String> answers) throws IOException, ParseException {
        HttpPost httpPost = new HttpPost(STRAW_POLL_API);
        httpPost.setEntity(createCreationRequestBody(question, answers));
        httpPost.setHeader("Accept", CONTENT_TYPE_JSON);
        httpPost.setHeader("Content-type", CONTENT_TYPE_JSON);

        CloseableHttpResponse response = HttpClientBuilder
                .create()
                .build()
                .execute(httpPost);

        String responseJSON = EntityUtils.toString(response.getEntity());
        JSONObject jsonObject = (JSONObject) new JSONParser().parse(responseJSON);

        return (Long) jsonObject.get("id");
    }

    public static SPPoll getResults(Long id) throws IOException, ParseException {
        HttpGet request = new HttpGet(STRAW_POLL_API + "/" + id);
        request.setHeader("Accept", CONTENT_TYPE_JSON);
        request.setHeader("Content-type", CONTENT_TYPE_JSON);

        CloseableHttpResponse response = HttpClientBuilder
                .create()
                .build()
                .execute(request);

        String responseJSON = EntityUtils.toString(response.getEntity());
        JSONObject jsonObject = (JSONObject) new JSONParser().parse(responseJSON);

        return new SPPoll(jsonObject);
    }

    /**
     * Votes for a Strawpoll poll with the given id, for the option at index i
     *
     * @param id    of Strawpoll poll
     * @param index of option
     * @return true if successfull
     * @throws IOException if something went wrong
     */
    public static boolean vote(long id, int index) throws IOException {
        SPAuth auth = getVoteAuth(id);

        HttpPost request = new HttpPost(STRAWPOLL_HOST + id);
        request.setEntity(createVoteRequestBody(index, auth));
        request.setHeader("Content-Type", CONTENT_TYPE_FORM_URLENCODED);

        return HttpClientBuilder
                .create()
                .build()
                .execute(request)
                .getStatusLine()
                .getStatusCode() == 200;
    }

    private static SPAuth getVoteAuth(long id) throws IOException {
        CloseableHttpClient client = HttpClientBuilder.create().build();
        HttpGet httpGet = new HttpGet(STRAWPOLL_HOST + id);

        CloseableHttpResponse response = client.execute(httpGet);
        String responseHTML = EntityUtils.toString(response.getEntity());

        Matcher secTokMatcher = SECURITY_TOKEN_PATTERN.matcher(responseHTML);
        if (!secTokMatcher.find()) {
            throw new IllegalArgumentException("Failed to find security token");
        }
        String securityAuthToken = secTokMatcher.group(1);

        Matcher fieldAuthTokenMatcher = FIELD_AUTHENTICITY_TOKEN_PATTERN.matcher(responseHTML);
        if (!fieldAuthTokenMatcher.find()) {
            throw new IllegalArgumentException("Failed to find authenticity token");
        }
        String fieldAuthenticityToken = fieldAuthTokenMatcher.group(1);

        List<Long> options = new ArrayList<>();
        Matcher optionsMatcher = OPTIONS_PATTERN.matcher(responseHTML);
        while (optionsMatcher.find()) {
            options.add(Long.valueOf(optionsMatcher.group(1)));
        }
        return new SPAuth(securityAuthToken, fieldAuthenticityToken, options);
    }

    private static StringEntity createCreationRequestBody(String question, List<String> answers) throws UnsupportedEncodingException {
        HashMap body = new JSONObject();
        body.put("title", question);
        body.put("options", answers);
        body.put("multi", false);
        String rawJSON = body.toString();
        return new StringEntity(rawJSON);
    }

    private static StringEntity createVoteRequestBody(int index, SPAuth auth) throws UnsupportedEncodingException {
        return new StringEntity("security-token=" + auth.getSecurityToken()
                + "&" + auth.getFieldAuthenticityToken() + "="
                + "&" + "options" + "=" + auth.getOptions().get(index));
    }
}