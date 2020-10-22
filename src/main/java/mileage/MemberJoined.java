package mileage;

public class MemberJoined extends AbstractEvent {

    private Long id;
    private Long memberId;
    private String mdnNo;
    private String nickname;
    private String memberStatus;

    public MemberJoined(){
        super();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }
    public String getMdnNo() {
        return mdnNo;
    }

    public void setMdnNo(String mdnNo) {
        this.mdnNo = mdnNo;
    }
    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
    public String getMemberStatus() {
        return memberStatus;
    }

    public void setMemberStatus(String memberStatus) {
        this.memberStatus = memberStatus;
    }
}
