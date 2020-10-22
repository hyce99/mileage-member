# mileage-member

# Mileage 시스템

핸드폰번호로 간단히 회원 가입하여 포인트를 적립/사용할 수 있는 멤버십 마일리지 시스템에 휴면을 관리하는 조직이 신규로 신설됨

# Table of contents

- [Mileage 시스템](#---)
  - [서비스 시나리오](#서비스-시나리오)
  - [분석/설계](#분석설계)
  - [구현:](#구현-)
    - [DDD 의 적용](#ddd-의-적용)
    - [폴리글랏 퍼시스턴스](#폴리글랏-퍼시스턴스)
    - [동기식 호출 과 Fallback 처리](#동기식-호출-과-Fallback-처리)
    - [비동기식 호출 과 Eventual Consistency](#비동기식-호출-과-Eventual-Consistency)
  - [운영](#운영)
    - [CI/CD 설정](#cicd설정)
    - [동기식 호출 / 서킷 브레이킹 / 장애격리](#동기식-호출-서킷-브레이킹-장애격리)
    - [오토스케일 아웃](#오토스케일-아웃)
    - [무정지 재배포](#무정지-재배포)
  - [시나리오 수행 결과](#시나리오 수행 결과)

# 서비스 시나리오

기능적 요구사항
1. 관리자는 휴면관리 시스템에서 휴면대상 회원을 등록한다
1. 휴면관리 시스템은 휴면 대상 고객을 관리 한다. (관리 상태 : 휴면대상, 휴면, 파기)
1. 관리자는 휴면고객의 정보를 휴면으로 변경할 수 있다.
1. 회원은 회원탈퇴가 가능하며, 탈퇴 전 보유 포인트는 소멸되어야 하며 회원의정보는 삭제된다.
1. 휴면 메시지 발송 실패 시 휴면 대상 회원의 상태를 파기로 변경한다. (비동기, Saga) 
1. 휴면 회원이 휴면해제 요청 시 신규 회원가입처리 한다. (동기)
1. 관리자는 휴면대상 고객의 리스트 및 상태를 조회할 수 있다.(CQRS)

비기능적 요구사항
1. 트랜잭션
    1. 휴면회원은 휴면해제 요청 시 상태처리 전 신규 회원 가입 처리 한다. ( Sync 호출)
 
1. 장애격리
    1. 회원가입은 365일 24시간 받을 수 있어야 한다. 
        Async (event-driven), Eventual  Consistency
    1. 회원시스템이 과중하면 휴면해제를 잠시동안 받지 않고 잠시 후에 하도록 유도한다  
       Circuit breaker, fallback
 
1. 성능
    1. 관리자는 관리자페이지에서 휴면고객 리스트와 상태를 조회 가능하다. CQRS
    1. 회원이 휴면이 될때 알림을 줄 수 있다. (Event driven)

# 분석/설계
     
## Event Storming 결과
* MSAEz 로 모델링한 이벤트스토밍 결과
![image](https://user-images.githubusercontent.com/70302890/96823217-f552dc00-1466-11eb-9489-4c0b9085c722.png)

    

#
# 구현:

분석/설계 단계에서 도출된 헥사고날 아키텍처에 따라, 각 BC별로 대변되는 마이크로 서비스들을 스프링부트로 구현하였다. 구현한 각 서비스를 로컬에서 실행하는 방법은 아래와 같다 (각자의 포트넘버는 8081 ~ 808n 이다)

```
cd member
mvn spring-boot:run

cd dormantmember
mvn spring-boot:run 

cd managerMessage
mvn spring-boot:run  

cd managerpage
mvn spring-boot:run 

cd gatewqy
mvn spring-boot:run 
```

## DDD 의 적용

- 각 서비스내에 도출된 핵심 Aggregate Root 객체를 Entity 로 선언하였다:

```
package mileage;

@Entity
@Table(name = "DormantMember_table")
public class DormantMember {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private Long memberId;
    private String phoneNo;
    private String memberStatus;
    private String nickname;


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

    public String getMemberStatus() {
        return memberStatus;
    }

    public void setMemberStatus(String memberStatus) {
        this.memberStatus = memberStatus;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

}


```
- Entity Pattern 과 Repository Pattern 을 적용하여 JPA 를 통하여 다양한 데이터소스 유형 (RDB or NoSQL) 에 대한 별도의 처리가 없도록 데이터 접근 어댑터를 자동 생성하기 위하여 Spring Data REST 의 RestRepository 를 적용하였다
```
package mileage;

import org.springframework.data.repository.PagingAndSortingRepository;
import java.util.Optional;

public interface ManagerMessageRepository extends PagingAndSortingRepository<ManagerMessage, Long>{
    Optional<ManagerMessage> findByMemberId(Long memberId);

}
```
- 적용 후 REST API 의 테스트
```
# dormantmember 휴면대상회원 등록하기 (상태 : Pre-Dormant 로 신규 등록 됨)
http POST http://localhost:8082//dormantMembers phoneNo=01085581234 nickname=DOR1 memberStatus=Pre-Dormant memberId=2

# dormantmember 휴면대상회원 변경하기
http PATCH http://localhost:8082//dormantMembers/1 memberStatus=DORMANT

# managerpages 관리자 화면에서 휴면회원 내역 조회
http GET http://localhost:8084/managerpages/1 

```


## 폴리글랏 퍼시스턴스

서비스 개발과 운영자들에게 익숙한 언어인 SQL을 사용하면서, 무료로 사용 가능한 RDB인 maria DB를 사용하기로 하였다. 
이를 위해 aws의 RDS로 mariaDB를 생성하였고, application.yml 파일과 pom.xml에 maria DB관련 코드를 추가하였다.

```
# ManagerMessage.java

package mileage;


@Entity
@Table(name="ManagerMessage_table")
public class ManagerMessage {

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY) // mariadb를 사용하여 db에서 auto_increment 속성을 사용하여 GenerationType.IDENTITY으로 설정
    private Long id;
    private Long memberId;
    private String phoneNo;
    private String messageContents;
    private String messageStatus;
    private String memberStatus;

}


# ManagerMessageRepository.java
package mileage;

public interface ManagerMessageRepository extends PagingAndSortingRepository<ManagerMessage, Long>{
    Optional<ManagerMessage> findByMemberId(Long memberId);

}
# application.yml
  datasource:
    driver-class-name: org.mariadb.jdbc.Driver
    username: id
    password: password
    url: jdbc:mariadb://localhost:3306/test 

  jpa:
    database: mysql
    database-platform: org.hibernate.dialect.MySQL5InnoDBDialect
    properties:
      hibernate:
        show_sql: true
        format_sql: true

# pom.xml
  <dependency>
    <groupId>org.mariadb.jdbc</groupId>
    <artifactId>mariadb-java-client</artifactId>
    <scope>runtime</scope>
  </dependency>

  <dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-jdbc</artifactId>
  </dependency>

  <dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-test</artifactId>
  </dependency>
    
```

## 동기식 호출 과 Fallback 처리

분석단계에서의 조건 중 하나로 휴면 해제 시 신규 회원가입 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하기로 하였다. 호출 프로토콜은 이미 앞서 Rest Repository 에 의해 노출되어있는 REST 서비스를 FeignClient 를 이용하여 호출하도록 한다. 

- 포인트서비스를 호출하기 위하여 Stub과 (FeignClient) 를 이용하여 Service 대행 인터페이스 (Proxy) 를 구현 

```
# (member) MemberService.java

package mileage.external;

@FeignClient(name="member", url="${api.member.url}")
public interface MemberService {

    @RequestMapping(method= RequestMethod.POST, path="/members")
    public void join(@RequestBody Member member);

}
```

- 휴면해제를 받은 직후(@PreRemove) 신규회원 가입을 선 처리하도록 요청하도록 처리
```
# DormantMember.java (Entity)

@PreUpdate
    public void onPreUpdate() {

        if (this.getMemberStatus().equals("CLEAR")) {
            DormantMemberCleared dormantMemberCleared = new DormantMemberCleared();
            BeanUtils.copyProperties(this, dormantMemberCleared);
            dormantMemberCleared.setMemberStatus("CLEAR-DONE");
            dormantMemberCleared.publishAfterCommit();

            //Following code causes dependency to external APIs
            // it is NOT A GOOD PRACTICE. instead, Event-Policy mapping is recommended.

            mileage.external.Member member = new mileage.external.Member();
            // mappings goes here

            member.setMemberId(this.getMemberId());
            member.setNickname(this.getNickname());
            member.setPhoneNo(this.getPhoneNo());
            member.setMemberStatus("READY");

            DormantmemberApplication.applicationContext.getBean(mileage.external.MemberService.class)
                    .join(member);
        }else {

            System.out.println("## 3 ###");

        }

    } 
```

- 동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, 포인트 시스템이 장애가 나면 포인트 소멸도 못받는다는 것을 확인:


```
# 포인트 (point) 서비스를 잠시 내려놓음 (ctrl+c)

#탈퇴처리
http DELETE http://localhost:8081/members/1   #Fail
http DELETE http://localhost:8081/members/2   #Fail


#포인트서비스 재기동
cd point
mvn spring-boot:run

#탈퇴처리
http DELETE http://localhost:8081/members/1   #Success
http DELETE http://localhost:8081/members/2   #Success
```

- 또한 과도한 요청시에 서비스 장애가 도미노 처럼 벌어질 수 있다. (서킷브레이커, 폴백 처리는 운영단계에서 설명한다.)




## 비동기식 호출 / 시간적 디커플링 / 장애격리 / 최종 (Eventual) 일관성 테스트


회원가입이 이루어진 후에 메시지시스템으로 이를 알려주는 행위는 동기식이 아니라 비 동기식으로 처리하여 메시지 시스템의 처리를 위하여 회원가입이 블로킹 되지 않도록 처리한다.
 
- 이를 위하여 회원가입 기록을 남긴 후에 곧바로 회원가입이 되었다는 도메인 이벤트를 카프카로 송출한다(Publish)
 
```
package mileage;

@Entity
@Table(name="Member_table")
public class Member {

 ...
    @PostPersist
    public void onPostPersist(){
        MemberJoined memberJoined = new MemberJoined();
        BeanUtils.copyProperties(this, memberJoined);

        memberJoined.setMemberStatus("READY");
        memberJoined.publishAfterCommit();

    }

}
```
- 메시지 서비스에서는 회원가입 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다:

```
package mileage;

...

@Service
public class PolicyHandler{

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverMemberJoined_SendMsg(@Payload MemberJoined memberJoined) {
    
        if (memberJoined.isMe() && Objects.equals(memberJoined.getMemberStatus(), "READY")) {
            
            System.out.println("##### listener SendMsg : " + memberJoined.toJson());
            // 회원 가입 정보를 받았으니, 메시지 전송을 슬슬 시작해야지..
        }
    }    
    

}

```
실제 구현을 하자면, 회원가입 노티를 받고, 메시지 전송 후 정상 여부를 전달할테니, 우선 회원가입 정보를 DB에 받아놓은 후, 이후 처리는 해당 Aggregate 내에서 하면 되겠다.:
  
```
  @Autowired MessageRepository messageRepository;  
  
  @StreamListener(KafkaProcessor.INPUT)
    public void wheneverMemberJoined_SendMsg(@Payload MemberJoined memberJoined) {
        if (memberJoined.isMe() && Objects.equals(memberJoined.getMemberStatus(), "READY")) {
            Message message = new Message();

            message.setMemberId(memberJoined.getMemberId());
            message.setPhoneNo(memberJoined.getPhoneNo());
            message.setMessageContents("CONTENTS");
            message.setMessageStatus("READY");

            messageRepository.save(message);
        }
    }

```

메시지 시스템은 회원가입과 완전히 분리되어있으며, 이벤트 수신에 따라 처리되기 때문에, 메시지 시스템이 유지보수로 인해 잠시 내려간 상태라도 회원가입을 받는데 문제가 없다:
```
# 메시지 서비스 (message)를 잠시 내려놓음 (ctrl+c)

#회원가입 처리
http POST http://localhost:8081/members phoneNo=01012341234 nickname=TEST memberStatus=READY memberId=99   #Success
http POST http://localhost:8081/members phoneNo=01056785678 nickname=TEST1 memberStatus=READY memberId=100 #Success

#회원가입 상태 확인
http http://localhost:8081/members     # 회원 상태 안바뀜 확인

#메시지 서비스 기동
cd message
mvn spring-boot:run

#회원가입 상태 확인
http http://localhost:8081/members     # 회원의 상태가 "NORMAL"로 확인
```


# 운영

## 동기식 호출 / 서킷 브레이킹 / 장애격리

* 서킷 브레이킹 프레임워크의 선택: Spring FeignClient + Hystrix 옵션을 사용하여 구현함

시나리오는 회원가입(member)--> 가입이력(point) 시의 연결을 RESTful Request/Response 로 연동하여 구현이 되어있고, 회원가입 요청이 과도할 경우 CB 를 통하여 장애격리.

- Hystrix 를 설정:  요청처리 쓰레드에서 처리시간이 610 밀리가 넘어서기 시작하여 어느정도 유지되면 CB 회로가 닫히도록 (요청을 빠르게 실패처리, 차단) 설정
```
# application.yml      
feign:
  hystrix:
    enabled: true

hystrix:
  command:
    # 전역설정
    default:
      execution.isolation.thread.timeoutInMilliseconds: 610      

```

- 피호출 서비스(포인트:point) 의 임의 부하 처리 - 450 밀리에서 증감 230 밀리 정도 왔다갔다 하게
```
# (point) Forfeiture.java (Entity)

    @PrePersist
    public void onPrePersist(){

        ...
        
        try {
            System.out.println("Thread Sleep");
            Thread.currentThread().sleep((long) (450 + Math.random() * 230));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    
```

* 부하테스터 siege 툴을 통한 서킷 브레이커 동작 확인:
- 동시사용자 3명
- 10초 동안 실시


```
$ siege -c3 -t10S -v --content-type "application/json" 'http://member:8080/members POST {"phoneNo": "01085580000", "nickname":"SEQ1" ,"memberStatus":"READY"}'

** SIEGE 4.0.5
** Preparing 100 concurrent users for battle.
The server is now under siege...

HTTP/1.1 201     0.48 secs:     268 bytes ==> POST http://...:8080/members
HTTP/1.1 201     0.50 secs:     268 bytes ==> POST http://...:8080/members
HTTP/1.1 201     0.49 secs:     268 bytes ==> POST http://...:8080/members
HTTP/1.1 201     0.58 secs:     268 bytes ==> POST http://...:8080/members

* 요청이 과도하여 CB를 동작함 요청을 차단

HTTP/1.1 500     0.62 secs:     249 bytes ==> POST http://...:8080/members   
HTTP/1.1 500     0.62 secs:     249 bytes ==> POST http://...:8080/members
HTTP/1.1 500     0.61 secs:     249 bytes ==> POST http://...:8080/members
HTTP/1.1 500     0.62 secs:     249 bytes ==> POST http://...:8080/members

* 요청을 어느정도 돌려보내고나니, 기존에 밀린 일들이 처리되었고, 회로를 닫아 요청을 다시 받기 시작

HTTP/1.1 201     0.48 secs:     268 bytes ==> POST http://...:8080/members  
HTTP/1.1 201     0.50 secs:     268 bytes ==> POST http://...:8080/members
HTTP/1.1 201     0.49 secs:     268 bytes ==> POST http://...:8080/members
HTTP/1.1 201     0.58 secs:     268 bytes ==> POST http://...:8080/members

* 다시 요청이 쌓이기 시작하여 건당 처리시간이 증가 시작 => 회로 열기 => 요청 실패처리

HTTP/1.1 500     0.62 secs:     249 bytes ==> POST http://...:8080/members   
HTTP/1.1 500     0.62 secs:     249 bytes ==> POST http://...:8080/members

* 생각보다 빨리 상태 호전됨 - (건당 (쓰레드당) 처리시간이 회복) => 요청 수락

HTTP/1.1 201     2.24 secs:     268 bytes ==> POST http://...:8080/members
HTTP/1.1 201     2.32 secs:     268 bytes ==> POST http://...:8080/members
HTTP/1.1 201     2.16 secs:     268 bytes ==> POST http://...:8080/members



* 이후 이러한 패턴이 계속 반복되면서 시스템은 도미노 현상이나 자원 소모의 폭주 없이 잘 운영됨



```
siege -c3 -t10S -v --content-type "application/json" 'http://member:8080/members POST {"phoneNo": "01085580000", "nickname":"SEQ1" ,"memberStatus":"READY"}'

![image](https://user-images.githubusercontent.com/73006747/96666641-873ce500-1392-11eb-884e-3125036b2a43.png)
![image](https://user-images.githubusercontent.com/73006747/96666650-8c9a2f80-1392-11eb-8622-4181dd50608d.png)

- 운영시스템은 죽지 않고 지속적으로 CB 에 의하여 적절히 회로가 열림과 닫힘이 벌어지면서 자원을 보호하고 있음을 보여줌. 하지만, 64.71% 가 성공하였고, 36%가 실패했다는 것은 고객 사용성에 있어 좋지 않기 때문에 Retry 설정과 동적 Scale out (replica의 자동적 추가,HPA) 을 통하여 시스템을 확장 해주는 후속처리가 필요.

- Retry 의 설정 (istio)
- Availability 가 높아진 것을 확인 (siege)

![image](https://user-images.githubusercontent.com/73006747/96674127-01756580-13a3-11eb-8e78-065104f724a0.png)


### 오토스케일 아웃
앞서 CB 는 시스템을 안정되게 운영할 수 있게 해줬지만 사용자의 요청을 100% 받아들여주지 못했기 때문에 이에 대한 보완책으로 자동화된 확장 기능을 적용하고자 한다. 


- 포인트 서비스에 대한 replica 를 동적으로 늘려주도록 HPA 를 설정한다. 설정은 CPU 사용량이 4프로를 넘어서면 replica 를 10개까지 늘려준다:
```
kubectl autoscale deploy point --min=1 --max=10 --cpu-percent=4
```
![image](https://user-images.githubusercontent.com/73006747/96672038-434fdd00-139e-11eb-8962-c1d46814f24d.png)

- CB 에서 했던 방식대로 워크로드를 걸어준다.
```
$ siege -c3 -t10S -r10 --content-type "application/json" 'http://localhost:8080/members POST {"memberStatus": "READY"}',{"phoneNo": "01000000000"}',{"nickname": "A"}'
```
- 오토스케일이 어떻게 되고 있는지 모니터링을 걸어둔다:
```
kubectl get deploy point -w -n tutorial
```
- 어느정도 시간이 흐른 후 스케일 아웃이 벌어지는 것을 확인할 수 있다:

![image](https://user-images.githubusercontent.com/73006747/96671955-08e64000-139e-11eb-905d-6232daccfaab.png)



## 무정지 재배포

* 먼저 무정지 재배포가 100% 되는 것인지 확인하기 위해서 Autoscaler 이나 CB 설정을 제거함

- seige 로 배포작업 직전에 워크로드를 모니터링 함.
```
$ siege -c3 -t100S -v --content-type "application/json" 'http://member:8080/members POST {"phoneNo": "01085580000", "nickname":"SEQ1" ,"memberStatus":"READY"}'

```

- 새버전으로의 배포 시작
```
kubectl set image ...
```

- seige 의 화면으로 넘어가서 Availability 가 100% 미만으로 떨어졌는지 확인

![image](https://user-images.githubusercontent.com/73006747/96672363-0f28ec00-139f-11eb-8c71-5c919f3383ea.png)

배포기간중 Availability 가 평소 100%에서 60% 대로 떨어지는 것을 확인. 원인은 쿠버네티스가 성급하게 새로 올려진 서비스를 READY 상태로 인식하여 서비스 유입을 진행한 것이기 때문. 이를 막기위해 Readiness Probe 를 설정함:

```
# deployment.yaml 의 readiness probe 의 설정:


kubectl apply -f kubernetes/deployment.yaml
```

- 동일한 시나리오로 재배포 한 후 Availability 확인:

![image](https://user-images.githubusercontent.com/73006747/96672395-28319d00-139f-11eb-9382-5720c9b258bf.png)


배포기간 동안 Availability 가 변화없기 때문에 무정지 재배포가 성공한 것으로 확인됨.

# 시나리오 수행 결과

* AWS 서비스 기동 확인

```
root@labs--132893260:~# kubectl get all -n tutorial
NAME                           READY   STATUS    RESTARTS   AGE
pod/gateway-86bfd79f75-mkngm   1/1     Running   0          4m48s
pod/member-c97fdd6f8-m9dsm     1/1     Running   0          4m31s
pod/message-78f9b8bf45-lhjzx   1/1     Running   0          5m2s
pod/mypage-7bf8c8c586-wvmp9    1/1     Running   0          3m57s
pod/point-fb9dc86d4-62jtw      1/1     Running   0          4m11s
pod/siege-5c7c46b788-gvdzr     1/1     Running   0          157m

NAME              TYPE           CLUSTER-IP      EXTERNAL-IP                                                                    PORT(S)          AGE
service/gateway   LoadBalancer   10.100.43.212   a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com   8080:31637/TCP   4m40s
service/member    ClusterIP      10.100.187.38   <none>                                                                         8080/TCP         4m24s
service/message   ClusterIP      10.100.145.5    <none>                                                                         8080/TCP         4m54s
service/mypage    ClusterIP      10.100.120.78   <none>                                                                         8080/TCP         3m50s
service/point     ClusterIP      10.100.77.33    <none>                                                                         8080/TCP         4m6s

NAME                      READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/gateway   1/1     1            1           4m48s
deployment.apps/member    1/1     1            1           4m31s
deployment.apps/message   1/1     1            1           5m2s
deployment.apps/mypage    1/1     1            1           3m57s
deployment.apps/point     1/1     1            1           4m11s
deployment.apps/siege     1/1     1            1           157m

NAME                                 DESIRED   CURRENT   READY   AGE
replicaset.apps/gateway-86bfd79f75   1         1         1       4m48s
replicaset.apps/member-c97fdd6f8     1         1         1       4m31s
replicaset.apps/message-78f9b8bf45   1         1         1       5m2s
replicaset.apps/mypage-7bf8c8c586    1         1         1       3m57s
replicaset.apps/point-fb9dc86d4      1         1         1       4m11s
replicaset.apps/siege-5c7c46b788     1         1         1       157m


```

1. 회원은 핸드폰번호와 닉네임으로 회원가입이 가능하다.(회원가입 상태 : READY)

```
root@labs--132893260:~# http POST http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/members phoneNo=01085581234 nickname=SEQ1 memberStatus=READY memberId=1

HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:06:50 GMT
Location: http://member:8080/members/1
transfer-encoding: chunked

{
    "_links": {
        "member": {
            "href": "http://member:8080/members/1"
        }, 
        "self": {
            "href": "http://member:8080/members/1"
        }
    }, 
    "memberId": 1, 
    "memberStatus": "READY", 
    "nickname": "SEQ1", 
    "phoneNo": "01085581234"
}

```

2. 회원가입이 되면, 회원의 핸드폰번호로 알림메시지를 발송한다.
가입시 핸드폰 번호가 11자리인 경우 정상으로 상태변경

```
root@labs--132893260:~# http GET http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/members/1

HTTP/1.1 200 OK
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:07:02 GMT
transfer-encoding: chunked

{
    "_links": {
        "member": {
            "href": "http://member:8080/members/1"
        }, 
        "self": {
            "href": "http://member:8080/members/1"
        }
    }, 
    "memberId": 1, 
    "memberStatus": "NORMAL", 
    "nickname": "SEQ1", 
    "phoneNo": "01085581234"
}

```
회원상태가 정상인경우 포인트1000점을 생성한다.

```
root@labs--132893260:~# http GET http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/points/1

HTTP/1.1 200 OK
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:07:08 GMT
transfer-encoding: chunked

{
    "_links": {
        "point": {
            "href": "http://point:8080/points/1"
        }, 
        "self": {
            "href": "http://point:8080/points/1"
        }
    }, 
    "memberId": 1, 
    "memberStatus": "NORMAL", 
    "remainPoint": 1000, 
    "requirePoint": null
}

```

3. 회원은 포인트를 적립/사용이 가능하며, 잔여포인트가 관리된다. 이때, 회원상태가 정상인 경우만 적립/사용이 가능하다.

```
root@labs--132893260:~# http PATCH http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/points/1 requirePoint=530   

HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:07:19 GMT
transfer-encoding: chunked

{
    "_links": {
        "point": {
            "href": "http://point:8080/points/1"
        }, 
        "self": {
            "href": "http://point:8080/points/1"
        }
    }, 
    "memberId": 1, 
    "memberStatus": "NORMAL", 
    "remainPoint": 1530, 
    "requirePoint": 530
}

```
-> 회원상태가 정상이며 530 포인트가 적립되었다.


```
root@labs--132893260:~# http PATCH http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/points/1 requirePoint=-730

HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:07:25 GMT
transfer-encoding: chunked

{
    "_links": {
        "point": {
            "href": "http://point:8080/points/1"
        }, 
        "self": {
            "href": "http://point:8080/points/1"
        }
    }, 
    "memberId": 1, 
    "memberStatus": "NORMAL", 
    "remainPoint": 800, 
    "requirePoint": -730
}

```
-> 회원상태가 정상이며 730포인트가 사용되었다.


4. 회원은 회원탈퇴가 가능하며, 탈퇴 전 보유 포인트는 소멸되어야 하며 회원의 정보는 삭제된다.

```
root@labs--132893260:~# http DELETE http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/members/1

HTTP/1.1 204 No Content
Date: Tue, 20 Oct 2020 15:08:35 GMT

```
회원과, 포인트에 대상이 없는걸 볼수있다.

```
root@labs--132893260:~# http GET http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/members/1 

HTTP/1.1 404 Not Found
Date: Tue, 20 Oct 2020 15:08:52 GMT
content-length: 0

root@labs--132893260:~# http GET http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/points/1

HTTP/1.1 404 Not Found
Date: Tue, 20 Oct 2020 15:09:28 GMT
content-length: 0

```


5. 알림메시지 발송이 실패하면 회원의 상태를 비정상으로 변경한다. 
알림메시지는 회원 폰 번호의 정합성을 체크하여 상태를 변경한다.
회원가입시 번호를 잘못입력한다.

```
root@labs--132893260:~# http POST http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/members phoneNo=0108558 nickname=SEQ2 memberStatus=READY memberId=2

HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:09:56 GMT
Location: http://member:8080/members/2
transfer-encoding: chunked

{
    "_links": {
        "member": {
            "href": "http://member:8080/members/2"
        }, 
        "self": {
            "href": "http://member:8080/members/2"
        }
    }, 
    "memberId": 2, 
    "memberStatus": "READY", 
    "nickname": "SEQ2", 
    "phoneNo": "0108558"
}

```

-> 회원상태조회 – ABNORMAL을 볼수있다.

```
root@labs--132893260:~# http GET http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/members/2

HTTP/1.1 200 OK
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:10:04 GMT
transfer-encoding: chunked

{
    "_links": {
        "member": {
            "href": "http://member:8080/members/2"
        }, 
        "self": {
            "href": "http://member:8080/members/2"
        }
    }, 
    "memberId": 2, 
    "memberStatus": "ABNORMAL", 
    "nickname": "SEQ2", 
    "phoneNo": "0108558"
}

```

포인트에는 회원상태가 비정상이므로 가입시 포인트를 주지않는다.

```
root@labs--132893260:~# http GET http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/points/2

HTTP/1.1 200 OK
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:10:49 GMT
transfer-encoding: chunked

{
    "_links": {
        "point": {
            "href": "http://point:8080/points/2"
        }, 
        "self": {
            "href": "http://point:8080/points/2"
        }
    }, 
    "memberId": 2, 
    "memberStatus": "ABNORMAL", 
    "remainPoint": 0, 
    "requirePoint": null
}

```

비정상 가입된 회원이 포인트를 적립하려고하면 적립이 되지 않는다.

```
root@labs--132893260:~# http PATCH http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/points/2 requirePoint=100

HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:11:01 GMT
transfer-encoding: chunked

{
    "_links": {
        "point": {
            "href": "http://point:8080/points/2"
        }, 
        "self": {
            "href": "http://point:8080/points/2"
        }
    }, 
    "memberId": 2, 
    "memberStatus": "ABNORMAL", 
    "remainPoint": 0, 
    "requirePoint": 100
}

```

비정상 가입된 회원이 포인트를 사용하려고하면 사용이 되지 않는다.

```
root@labs--132893260:~# http PATCH http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/points/2 requirePoint=-50

HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:11:09 GMT
transfer-encoding: chunked

{
    "_links": {
        "point": {
            "href": "http://point:8080/points/2"
        }, 
        "self": {
            "href": "http://point:8080/points/2"
        }
    }, 
    "memberId": 2, 
    "memberStatus": "ABNORMAL", 
    "remainPoint": 0, 
    "requirePoint": -50
}

```

비정상 멤버를 삭제한다. 회원과 포인트에서 회원정보가 없다.

```
root@labs--132893260:~# http DELETE http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/members/2

HTTP/1.1 204 No Content
Date: Tue, 20 Oct 2020 15:11:44 GMT

root@labs--132893260:~# http GET http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/members/2 

HTTP/1.1 404 Not Found
Date: Tue, 20 Oct 2020 15:11:55 GMT
content-length: 0

root@labs--132893260:~# http GET http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/points/2

HTTP/1.1 404 Not Found
Date: Tue, 20 Oct 2020 15:12:03 GMT
content-length: 0

```

6. 마이페이지에서는 회원의 핸드폰번호/닉네임/잔여포인트가 조회 가능하다.
신규회원을 가입시키고 마이페이지에서 회원정보를 조회한다.

```
root@labs--132893260:~# http POST http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/members phoneNo=01011223344 nickname=SEQ4 memberStatus=READY memberId=3

HTTP/1.1 201 Created
Content-Type: application/json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:12:10 GMT
Location: http://member:8080/members/3
transfer-encoding: chunked

{
    "_links": {
        "member": {
            "href": "http://member:8080/members/3"
        }, 
        "self": {
            "href": "http://member:8080/members/3"
        }
    }, 
    "memberId": 3, 
    "memberStatus": "READY", 
    "nickname": "SEQ4", 
    "phoneNo": "01011223344"
}

```

마이페이지에서 정상가입된 회원의 상태와 포인트 정보를 볼수있다.

```
root@labs--132893260:~# http GET http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/mypages/3

HTTP/1.1 200 OK
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:12:16 GMT
transfer-encoding: chunked

{
    "_links": {
        "mypage": {
            "href": "http://mypage:8080/mypages/3"
        }, 
        "self": {
            "href": "http://mypage:8080/mypages/3"
        }
    }, 
    "memberId": 3, 
    "memberStatus": "NORMAL", 
    "nickname": "SEQ4", 
    "phoneNo": "01011223344", 
    "remainPoint": 1000
}

```

신규회원의 포인트를 적립해본뒤 마이페이지에서 변경된 상태를 재확인한다.

```
root@labs--132893260:~# http PATCH http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/points/3 requirePoint=500

HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:12:52 GMT
transfer-encoding: chunked

{
    "_links": {
        "point": {
            "href": "http://point:8080/points/3"
        }, 
        "self": {
            "href": "http://point:8080/points/3"
        }
    }, 
    "memberId": 3, 
    "memberStatus": "NORMAL", 
    "remainPoint": 1500, 
    "requirePoint": 500
}


root@labs--132893260:~# http GET http://a581985ad3ce74724b22d67aa2a393da-1904051125.ap-southeast-2.elb.amazonaws.com:8080/mypages/3

HTTP/1.1 200 OK
Content-Type: application/hal+json;charset=UTF-8
Date: Tue, 20 Oct 2020 15:12:57 GMT
transfer-encoding: chunked

{
    "_links": {
        "mypage": {
            "href": "http://mypage:8080/mypages/3"
        }, 
        "self": {
            "href": "http://mypage:8080/mypages/3"
        }
    }, 
    "memberId": 3, 
    "memberStatus": "NORMAL", 
    "nickname": "SEQ4", 
    "phoneNo": "01011223344", 
    "remainPoint": 1500
}
root@labs--132893260:~#
'''






