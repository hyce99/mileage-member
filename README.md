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
# 회원가입 (member) 서비스를 잠시 내려놓음 (ctrl+c)

#휴면해제 처리
http PATCH http://localhost:8082//dormantMembers/1 memberStatus=CLEAR   #Fail

```
![image](https://user-images.githubusercontent.com/70302890/96826336-1ff46300-146e-11eb-9498-0a07f415fb4f.png)


```
#회원서비스 재기동
cd member
mvn spring-boot:run

#휴면해제 처리
http PATCH http://localhost:8082//dormantMembers/1 memberStatus=CLEAR   #Success

```
![image](https://user-images.githubusercontent.com/70302890/96826457-5d58f080-146e-11eb-8ef4-45c5dea48491.png)

```

- 또한 과도한 요청시에 서비스 장애가 도미노 처럼 벌어질 수 있다. (서킷브레이커, 폴백 처리는 운영단계에서 설명한다.)




## 비동기식 호출 / 시간적 디커플링 / 장애격리 / 최종 (Eventual) 일관성 테스트


관리자가 회원을 휴면으로 변경 시 회원에게 휴면 메시지를 발송은 비 동기식으로 처리하여 메시지 시스템의 처리를 위하여 휴면처기가 블로킹 되지 않도록 처리한다.
 
- 이를 위하여 회원휴면 처리 후 기록을 남긴 후에 곧바로 회원 휴면처리 이 되었다는 도메인 이벤트를 카프카로 송출한다(Publish)
 
```

```
package mileage;

@Entity
@Table(name = "DormantMember_table")
public class DormantMember {

 ...
@PostUpdate
    public void onPostUpdate() {
        if (this.getMemberStatus().equals("DORMANT")) {
            DormantMemberChanged dormantMemberChanged = new DormantMemberChanged();
            BeanUtils.copyProperties(this, dormantMemberChanged);

            System.out.println("### 1 ##");

            dormantMemberChanged.publishAfterCommit();

        } else if (this.getMemberStatus().equals("DESTRUCTION")) {
            DormantStatusUpdated dormantStatusUpdated = new DormantStatusUpdated();
            BeanUtils.copyProperties(this, dormantStatusUpdated);

            System.out.println("### 2 ##");

            dormantStatusUpdated.publishAfterCommit();

        }

    }
```
-관리자 메시지 서비스에서는 휴면요청 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다:

```
package mileage;

...

@Service
public class PolicyHandler{

    @Autowired
    ManagerMessageRepository ManagerMessageRepository;

    @StreamListener(KafkaProcessor.INPUT)
    public void onStringEventListener(@Payload String eventString){

    }

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverDormantMemberChanged_SendManagerMsg(@Payload DormantMemberChanged dormantMemberChanged){

        if(dormantMemberChanged.isMe()){
            System.out.println("##### listener SendManagerMsg : " + dormantMemberChanged.toJson());
            ManagerMessage managerMessage = new ManagerMessage();
            managerMessage.setMemberId(dormantMemberChanged.getMemberId());
            managerMessage.setPhoneNo(dormantMemberChanged.getPhoneNo());
            managerMessage.setMessageContents("DORMANT Message Send~!");
            managerMessage.setMessageStatus("");

            ManagerMessageRepository.save(managerMessage);
        }
    }
    

}

```

관리자 메시지 시스템은 회원해제와 완전히 분리되어있으며, 이벤트 수신에 따라 처리되기 때문에, 과리자메시지 시스템이 유지보수로 인해 잠시 내려간 상태라도 회원휴면처리를 할 수 있다.
그리고 휴면 처리 메시지가 정상 발송 되지 않으면, 휴면대상 회원의 상태를 파기로 변경한다. (Saga)
```
![image](https://user-images.githubusercontent.com/70302890/96827122-d1e05f00-146f-11eb-8756-4eedac8073e4.png)

```
# 운영

## 동기식 호출 / 서킷 브레이킹 / 장애격리

* 서킷 브레이킹 프레임워크의 선택: Spring FeignClient + Hystrix 옵션을 사용하여 구현함

시나리오는 회원이 휴면 해제 요청 (dormantmember)--> 신규회원 등록(member) 시의 연결을 RESTful Request/Response 로 연동하여 구현이 되어있고, 
휴면 해제 요청이 다량 휴입 시 CB 를 통하여 장애격리.

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

- 피호출 서비스(회원:member) 의 임의 부하 처리 - 450 밀리에서 증감 230 밀리 정도 왔다갔다 하게
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


### 오토스케일 아웃
앞서 CB 는 시스템을 안정되게 운영할 수 있게 해줬지만 사용자의 요청을 100% 받아들여주지 못했기 때문에 이에 대한 보완책으로 자동화된 확장 기능을 적용하고자 한다. 


-  서비스에 대한 replica 를 동적으로 늘려주도록 HPA 를 설정한다. 설정은 CPU 사용량이 4프로를 넘어서면 replica 를 10개까지 늘려준다:
```
kubectl autoscale deploy member --min=1 --max=10 --cpu-percent=4
```
![image](https://user-images.githubusercontent.com/70302890/96828746-2507e100-1473-11eb-8bb7-6d3f13396d70.png)

- CB 에서 했던 방식대로 워크로드를 걸어준다. (2명, 10초)
```
$ siege -c2 -t10S -v --content-type "application/json" 'http://dormantmember:8080/dormantMembers PATCH {"memberStatus":"CREAR"}'
```
- 오토스케일이 어떻게 되고 있는지 모니터링을 걸어둔다:
```
kubectl get deploy member -w
```
- 어느정도 시간이 흐른 후 스케일 아웃이 벌어지는 것을 확인할 수 있다:

![image](https://user-images.githubusercontent.com/70302890/96829053-c0995180-1473-11eb-8cb8-bb919bb1c453.png)



## 무정지 재배포

- seige 로 배포작업 직전에 워크로드를 모니터링 함.
```
$ siege -c3 -t100S -v --content-type "application/json" 'http://member:8080/members POST {"phoneNo": "01085580000", "nickname":"SEQ1" ,"memberStatus":"READY"}'

```

- 새버전으로의 배포 시작

- seige 의 화면으로 넘어가서 Availability 가 100% 미만으로 떨어졌는지 확인


배포기간중 Availability 가 평소 100%에서 80% 대로 떨어지는 것을 확인. 원인은 쿠버네티스가 성급하게 새로 올려진 서비스를 READY 상태로 인식하여 서비스 유입을 진행한 것이기 때문. 이를 막기위해 Readiness Probe 를 설정함:

```
# deployment.yaml 의 readiness probe 의 설정:
```

![image](https://user-images.githubusercontent.com/70302890/96830364-4f0ed280-1476-11eb-9a38-75468b980ae2.png)

kubectl apply -f kubernetes/deployment.yaml
```

- 동일한 시나리오로 재배포 한 후 Availability 확인:
```

![image](https://user-images.githubusercontent.com/70302890/96830083-c728c880-1475-11eb-8145-098f85345df5.png)


배포기간 동안 Availability 가 변화없기 때문에 무정지 재배포가 성공한 것으로 확인됨.

liveness Probe

![image](https://user-images.githubusercontent.com/70302890/96834067-5507b200-147c-11eb-8bd0-0b86cffd1b45.png)

![image](https://user-images.githubusercontent.com/70302890/96833989-373a4d00-147c-11eb-97d1-dd93f1190916.png)



# 시나리오 수행 결과

* AWS 서비스 기동 확인


(시나리오 1) 휴면대상으로 등록된 회원을 관리자가 휴면으로 변경 시 회원에게 휴대폰 알림 발송이 오류가 난 경우 → 회원의 상태를 파기 (DESTRUCTION)로 변경함 (saga)

```
http POST http://a8f9c6d333ec14e2daef4bede64caa2b-1593742903.ap-southeast-2.elb.amazonaws.com:8080/dormantMembers phoneNo=1234567 nickname=DOR-Saga memberStatus=Pre-Dormant memberId=1

```
![image](https://user-images.githubusercontent.com/70302890/96830624-b0cf3c80-1476-11eb-970a-12baa554d9cd.png)

```

http PATCH http://a8f9c6d333ec14e2daef4bede64caa2b-1593742903.ap-southeast-2.elb.amazonaws.com:8080//dormantMembers/1 memberStatus=DORMANT

```

![image](https://user-images.githubusercontent.com/70302890/96830662-c3e20c80-1476-11eb-8ec6-5dc4b1abafd1.png)

```

http GET http://a8f9c6d333ec14e2daef4bede64caa2b-1593742903.ap-southeast-2.elb.amazonaws.com:8080/dormantMembers/1

```

![image](https://user-images.githubusercontent.com/70302890/96830701-d2302880-1476-11eb-8d8d-0d59e99fe037.png)

```

(시나리오 2) 휴면대상회원 등록하기 (상태 : Pre-Dormant 로 신규 등록 됨)


http POST http://a8f9c6d333ec14e2daef4bede64caa2b-1593742903.ap-southeast-2.elb.amazonaws.com:8080/dormantMembers phoneNo=01052995000 nickname=DOR1 memberStatus=Pre-Dormant memberId=2
```

![image](https://user-images.githubusercontent.com/70302890/96830791-effd8d80-1476-11eb-935d-80163641025c.png)

```
http GET http://a8f9c6d333ec14e2daef4bede64caa2b-1593742903.ap-southeast-2.elb.amazonaws.com:8080/dormantMembers/2

```
![image](https://user-images.githubusercontent.com/70302890/96830832-fee44000-1476-11eb-9342-22df1817b7e2.png)


(시나리오 3) 관리자가 휴면상태로 변경 (상태 : DORMANT 로 변경되고, 회원에게 메시지를 전송함 - 비동기 호출)

```
http PATCH http://a8f9c6d333ec14e2daef4bede64caa2b-1593742903.ap-southeast-2.elb.amazonaws.com:8080/dormantMembers/2 memberStatus=DORMANT 

```
![image](https://user-images.githubusercontent.com/70302890/96830876-13283d00-1477-11eb-9d18-934afa31b0bb.png)

```
http GET http://a8f9c6d333ec14e2daef4bede64caa2b-1593742903.ap-southeast-2.elb.amazonaws.com:8080/managerMessages/2

```
![image](https://user-images.githubusercontent.com/70302890/96830918-21765900-1477-11eb-8313-29ff695321ab.png)


(시나리오 4) 회원이 휴면 해제(CLEAR) 요청 시 회원상태를 'READY'로 변경 후 회원의 신규 회원 등록 처리함. (동기)

```
http PATCH http://a8f9c6d333ec14e2daef4bede64caa2b-1593742903.ap-southeast-2.elb.amazonaws.com:8080/dormantMembers/2 memberStatus=CLEAR

```
![image](https://user-images.githubusercontent.com/70302890/96830988-381cb000-1477-11eb-9ddd-3c0f338e5109.png)


(시나리오 5) 관리자 화면에서 휴면회원 내역 조회 (CQRS)

```
http GET http://a8f9c6d333ec14e2daef4bede64caa2b-1593742903.ap-southeast-2.elb.amazonaws.com:8080/managerpages/
```
![image](https://user-images.githubusercontent.com/70302890/96831118-6f8b5c80-1477-11eb-8b64-707dcc3c79d4.png)
```



