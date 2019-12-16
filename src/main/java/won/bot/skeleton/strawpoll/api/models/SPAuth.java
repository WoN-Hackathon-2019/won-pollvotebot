package won.bot.skeleton.strawpoll.api.models;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class SPAuth {

    @Getter
    @Setter
    private String securityToken;

    @Getter
    @Setter
    private String fieldAuthenticityToken;

    @Getter
    @Setter
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