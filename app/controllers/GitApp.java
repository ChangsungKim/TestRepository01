/**
 * Yobi, Project Hosting SW
 *
 * Copyright 2012 NAVER Corp.
 * http://yobi.io
 *
 * @Author Ahn Hyeok Jun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package controllers;

import java.io.IOException;

import javax.servlet.ServletException;

import models.Project;
import models.enumeration.Operation;

import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.With;
import playRepository.PlayRepository;
import playRepository.RepositoryService;
import utils.AccessControl;
import utils.BasicAuthAction;

public class GitApp extends Controller {

    /**
     * 주어진 {@code service}가 지원되는지의 여부를 반환한다.
     *
     * when: Git 클라이언트가 Git 서버에 서비스 요청을 했을 때
     *
     * "git-upload-pack"과 "git-receive-pack" 서비스만을 지원한다.
     *
     * @param service 지원되는지 물어볼 서비스
     * @return {@code service}가 지원되는지의 여부
     */
    public static boolean isSupportedService(String service) {
        return service != null
                && (service.equals("git-upload-pack") || service.equals("git-receive-pack"));
    }

    /**
     * {@code project}의 Git 저장소에 대해 현재 사용자가 {@code service}를 요청할 권한이 있는지의 여부를
     * 반환한다.
     *
     * when: Git 클라이언트가 Git 서버에 서비스 요청을 했을 때
     *
     * "git-upload-pack" 서비스의 경우, 저장소에 읽기({@link Operation#READ}) 권한이 있는지, 그 외의
     * 서비스의 경우, 저장소에 갱신({@link Operation#UPDATE} 권한이 있는지 검사한다.
     *
     * @param project Git 저장소가 속한 프로젝트
     * @param service 수행할 권한이 있는지 물어볼 서비스
     * @return 권한이 있는지의 여부
     * @throws UnsupportedOperationException
     * @throws IOException
     * @throws ServletException
     */
    private static boolean isAllowed(Project project, String service) throws
            UnsupportedOperationException, IOException, ServletException {
        Operation operation = Operation.UPDATE;
        if (service.equals("git-upload-pack")) {
            operation = Operation.READ;
        }

        PlayRepository repository = RepositoryService.getRepository(project);
        return AccessControl
                .isAllowed(UserApp.currentUser(), repository.asResource(), operation);

    }

    /**
     * Git 서버에 대한 {@code service} 요청을 처리한다.
     *
     * when: Git 클라이언트가 Git 서버에 서비스 요청을 했을 때
     *
     * {@code ownerName}과 {@code projectName}에 대응하는 프로젝트의 코드 저장소에 대해,
     * {@code service} 요청을 수행하고 그 결과를 응답으로 돌려준다.
     *
     * @param ownerName 프로젝트 소유자 이름
     * @param projectName 프로젝트 이름
     * @param service 요청하는 서비스
     * @param isAdvertise advertise에 대한 요청인지의 여부
     * @return 요청에 대한 응답
     * @throws IOException
     * @throws UnsupportedOperationException
     * @throws ServletException
     */
    public static Result service(String ownerName, String projectName, String service,
            boolean isAdvertise) throws IOException, UnsupportedOperationException,
            ServletException {
        if (!isSupportedService(service)) {
            return forbidden(String.format("Unsupported service: '%s'", service));
        }

        Project project = Project.findByOwnerAndProjectName(ownerName, projectName);

        if (project == null) {
            return notFound();
        }

        models.User user = UserApp.currentUser();

        if (!isAllowed(project, service)) {
            if (user.isAnonymous()) {
                return BasicAuthAction.unauthorized(response());
            } else {
                // If you want the Git client showing your custom message to
                // the user, the Content-Type should be exact "text/plain"
                // without any parameter. For more details, see the code at
                // https://github.com/git/git/commit/426e70d4a11ce3b4f70636d57c6a0ab16ae08a00#diff-eea0ad565ec5903a11b6023755d491cfR154
                response().setHeader("Content-Type", "text/plain");
                return forbidden(
                        String.format("'%s' has no permission to '%s/%s'.",
                            user.name, ownerName, projectName));
            }
        }

        if (isAdvertise) {
            return ok(RepositoryService
                    .gitAdvertise(project, service, response()));
        } else {
            if (request().body().isMaxSizeExceeded()) {
                return status(REQUEST_ENTITY_TOO_LARGE);
            } else {
                user.visits(project);
                return ok(RepositoryService
                        .gitRpc(project, service, request(), response()));
            }
        }
    }

    /**
     * Git 서버에 대한 advertise 요청을 처리한다.
     *
     * when: Git 클라이언트가 Git 서버에 advertise 요청을 했을 때
     *
     * 요청을 처리하기 전에, {@link BasicAuthAction}으로 사용자를 인증한다.
     *
     * {@code service}가 주어지지 않은 경우에는 본래 getanyfile 서비스를 수행해야 하나, 현재 지원하지
     * 않으므로 403 Forbidden 으로 응답한다. 이는 Git의 {@code http-backend.c}가 동작하는 방식을 그대로
     * 따른 것이다.
     *
     * @param ownerName 프로젝트 소유자 이름
     * @param projectName 프로젝트 이름
     * @param service 요청하는 서비스
     * @return 요청에 대한 응답
     * @throws UnsupportedOperationException
     * @throws IOException
     * @throws ServletException
     */
    @With(BasicAuthAction.class)
    public static Result advertise(String ownerName, String projectName, String service)
            throws UnsupportedOperationException, IOException, ServletException {
        if (service == null) {
            // If service parameter is not specified then git server should do getanyfile service,
            // but we don't support that.
            return forbidden("Unsupported service: getanyfile");
        }
        return GitApp.service(ownerName, projectName, service, true);
    }

    /**
     * Git 서버에 대한 RPC 요청을 처리한다.
     *
     * when: Git 클라이언트가 Git 서버에 RPC 요청을 했을 때
     *
     * RPC 요청시의 {@code service}는 "git-upload-pack"과 "git-receive-pack" 뿐이며, 이외의 경우는 없다.
     *
     * 요청을 처리하기 전에, {@link BasicAuthAction}으로 사용자를 인증한다.
     *
     * @param ownerName 프로젝트 소유자 이름
     * @param projectName 프로젝트 이름
     * @param service 요청하는 서비스
     * @return 요청에 대한 응답
     * @throws UnsupportedOperationException
     * @throws IOException
     * @throws ServletException
     */
    @With(BasicAuthAction.class)
    public static Result serviceRpc(String ownerName, String projectName, String service)
            throws UnsupportedOperationException, IOException, ServletException {
        return GitApp.service(ownerName, projectName, service, false);
    }

}
