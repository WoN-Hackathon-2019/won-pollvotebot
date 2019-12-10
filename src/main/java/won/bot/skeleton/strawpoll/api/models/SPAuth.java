package won.bot.skeleton.strawpoll.api.models;

import java.util.List;

public class SPAuth {

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