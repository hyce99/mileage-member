package mileage;

import javax.persistence.*;
import org.springframework.beans.BeanUtils;
import java.util.List;

@Entity
@Table(name="Member_table")
public class Member {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private Long memberId;
    private String phoneNo;
    private String nickname;
    private String memberStatus;

    @PostPersist
    public void onPostPersist(){
        MemberJoined memberJoined = new MemberJoined();
        BeanUtils.copyProperties(this, memberJoined);
        memberJoined.publishAfterCommit();


    }

    @PostUpdate
    public void onPostUpdate(){
        MemberStatusChanged memberStatusChanged = new MemberStatusChanged();
        BeanUtils.copyProperties(this, memberStatusChanged);
        memberStatusChanged.publishAfterCommit();


    }

    @PreRemove
    public void onPreRemove(){
        MemberWithdrawn memberWithdrawn = new MemberWithdrawn();
        BeanUtils.copyProperties(this, memberWithdrawn);
        memberWithdrawn.publishAfterCommit();


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
    public String getPhoneNo() {
        return phoneNo;
    }

    public void setPhoneNo(String phoneNo) {
        this.phoneNo = phoneNo;
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
