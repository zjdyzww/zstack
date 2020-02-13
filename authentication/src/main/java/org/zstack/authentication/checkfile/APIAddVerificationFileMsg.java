package org.zstack.authentication.checkfile;

import org.springframework.http.HttpMethod;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.APIParam;
import org.zstack.header.rest.RestRequest;

@RestRequest(
        path = "/authentication/file/actions",
        method = HttpMethod.PUT,
        isAction = true,
        responseClass = APIAddVerificationFileEvent.class
)
public class APIAddVerificationFileMsg extends APIMessage {
    @APIParam
    private String path;

    @APIParam
    private String node;

    @APIParam
    private String hexType;

    @APIParam(maxLength = 64)
    private String category;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }

    public String getHexType() {
        return hexType;
    }

    public void setHexType(String hexType) {
        this.hexType = hexType;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public static APIAddVerificationFileMsg __example__(){
        APIAddVerificationFileMsg msg = new APIAddVerificationFileMsg();
        msg.setPath("/usr/local/zstack/VERSION");
        msg.setNode("mn");
        msg.setHexType("md5");
        msg.setCategory("versionFile");
        return msg;
    }

}