import ar.com.fdvs.dj.domain.builders.FastReportBuilder
import ar.com.fdvs.dj.domain.DynamicReport
import ar.com.fdvs.dj.core.DynamicJasperHelper
import ar.com.fdvs.dj.core.layout.ClassicLayoutManager
import ar.com.fdvs.dj.domain.entities.columns.AbstractColumn
import ar.com.fdvs.dj.domain.builders.ColumnBuilder
import net.sf.jasperreports.engine.JRDataSource
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource
import net.sf.jasperreports.engine.JasperPrint
import net.sf.jasperreports.engine.JRException
import net.sf.jasperreports.engine.export.JRPdfExporter
import net.sf.jasperreports.engine.JRExporter
import net.sf.jasperreports.engine.JRExporterParameter
import ar.com.fdvs.dj.domain.DJCalculation
import ar.com.fdvs.dj.domain.constants.GroupLayout
import java.text.SimpleDateFormat
import org.json.JSONObject
import net.sf.jasperreports.engine.export.JRXlsExporter
import net.sf.jasperreports.engine.export.JRXlsExporterParameter
import ar.com.fdvs.dj.core.layout.ListLayoutManager
import grails.converters.JSON
import org.compass.core.CompassQuery

class ReportsController extends BaseController {

    static allowedMethods = [vote: "POST"]

    def index = { redirect(action: "list", params: params) }

    DatabaseService databaseService
    def searchableService

    def beforeInterceptor = [action: this.&auth]

    def auth() {
        try {
            findLoggedUser()
            return true
        } catch (Exception e) {
            redirect(controller: 'login', action: 'auth')
            return false
        }
    }

    def ajaxVote = {
        def user = findLoggedUser()
        def learning = Learning.get(params.learningId)
        JSONObject jsonResponse = new JSONObject()

        def vote = LearningVote.withCriteria {
            eq("user", user)
            eq("learning", learning)
        }

        if (vote) {
            jsonResponse.put('message', g.message(code: "reports.vote.twice"))
            jsonResponse.put('points', learning.points)
        } else if (learning.user == user) {
            jsonResponse.put('message', g.message(code: "reports.vote.own.learning"))
            jsonResponse.put('points', learning.points)
        } else {
            if (learning.points != null) {
                learning.points++
            } else {
                learning.points = 1
            }
            learning.save(flush: true)

            LearningVote learningVote = new LearningVote()
            learningVote.user = user
            learningVote.date = new Date()
            learningVote.vote = 1
            learningVote.learning = learning
            learningVote.save(flush: true)

            ScoreManager.addPoints(user, "KNOWLEDGE", "VOTE")
            ScoreManager.addPoints(learning.user, "KNOWLEDGE", "VOTERECEIVED")

            jsonResponse.put('points', learning.points)
            jsonResponse.put('message', g.message(code: "reports.vote.ok"))
        }
        render jsonResponse.toString()
    }

    def knowledge = {
        def user = findLoggedUser()

        params.max = Math.min(params?.max?.toInteger() ?: 10, 100)
        params.offset = params?.offset?.toInteger() ?: 0
        params.sort = params?.sort ?: "date"
        params.order = params?.order ?: "desc"

        def learnings = []
        def total

        if (params.search) {
            def result = searchableService.search([offset: params.offset, max: params.max,escape: true]) {
                alias('Learning')
                must(term('company', user.company))
                must(queryString(params.search) {
                    useOrDefaultOperator()
                    setDefaultSearchProperty('description')
                })
                sort(CompassQuery.SortImplicitType.SCORE)
            }
            learnings = result.results


            total = searchableService.countHits([escape: true]) {
                alias('Learning')
                must(term('company', user.company))
                must(term('description', params.search))
            }
        } else {
            learnings = Learning.createCriteria().list(
                    max: params.max,
                    offset: params.offset) {
                eq "company", user.company
                order(params.sort, params.order)
            }
            total = learnings.totalCount
        }

        if (params.learningSaved)
            flash.message = "reports.knowledge.new.learning.saved"

        render(view: 'knowledge', model: [user: user, learnings: learnings, totalLearnings: total, search: params.search])
    }


    def newKnowledge = {
        def user = findLoggedUser()
        if (!g.message(code: "reports.knowledge.new.learning.empty").equals(flash.message)) {
            flash.message = ""
        }
        render(view: 'newKnowledge', model: [user: user])
    }

    def list = {
        def user = findLoggedUser()
        def projects = Project.withCriteria() {
            eq("company", user.company)
            eq("active", Boolean.TRUE)
            order("name", "asc")

        }
        def roles = Role.findAllByCompany(user.company, [sort: 'name'])
        def users = User.findAllByCompany(user.company, [sort: 'name'])

        [projects: projects, roles: roles, users: users]

    }

    def ranking = {
        def user = findLoggedUser()
        def List rankingList = new ArrayList()
        def rankingTotal = 0
        String startDate, endDate
        Date dateStart, dateEnd

        // If there are dates, perform search, if not, just show datepickers
        if (params.dateStart != null && params.dateEnd != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")

            dateStart = new Date(params.dateStart)
            startDate = sdf.format(dateStart)
            dateEnd = new Date(params.dateEnd)
            endDate = sdf.format(dateEnd)

            rankingList = databaseService.getReportRanking(startDate, endDate, user.company)
            rankingTotal = databaseService.getReportRankingTotalScore(startDate, endDate, user.company)

            if (rankingList?.empty)
                flash.message = g.message(code: "reports.search.empty")

        }
        return [userInstance: user, ranking: rankingList, rankingTotal: rankingTotal, startDate: startDate, endDate: endDate, startDateValue: dateStart, endDateValue: dateEnd]
    }

    def showUserInfo = {

        def user = User.get(params.id)
        def loggedUser = findLoggedUser()

        if (user.company != loggedUser.company) {
            params.clear()
            flash.message = "CHEAAAAAAATER" //FIXME: WTF?
            redirect(action: "knowledge", params: params)
        }


        def assignments = user.listActiveAssignments()
        def totalUsers = User.countByCompany(loggedUser.company)
        def userRanking = databaseService.getReportUserHistoricRanking(user)

        /*FedeF
        * TODO: Ahora esta harcodeada la fecha de abajo porque en la tabla usuarios hay algunos que no tienen completo el campo joined, llenar la tabla o establecer una fecha.
        */
        String startDate,endDate;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")
        endDate = sdf.format(new Date());
        try{
            startDate=sdf.format(user.joined)
        }catch(Exception e){
            startDate="2005-01-01"
        }
        def points = databaseService.getUsersPoints(startDate, endDate, user.company, user.id)[0].points
        return [userInstance: user, availablesChatTime: TimeZoneUtil.getAvailablePromptTimes(), timeZones: TimeZoneUtil.getAvailableTimeZones(), locales: LocaleUtil.getAvailableLocales(), assignments: assignments, totalUsers: totalUsers, userRanking: userRanking, userPoints: points]
    }

    def saveNewLearning = {

        def user = findLoggedUser()

        String learningContent = params.learning?.trim()

        if (!learningContent) {
            params.messageEmpty = true
            flash.message = g.message(code: "reports.knowledge.new.learning.empty")
            redirect(action: "newKnowledge")
            return
        }

        Learning newLearning

        GregorianCalendar calendar = GregorianCalendar.getInstance();
        Date date = new Date(calendar.getTimeInMillis());

        newLearning = new Learning()
        newLearning.date = date
        newLearning.user = user
        newLearning.company = user.company
        newLearning.points = 0
        newLearning.description = learningContent
        newLearning.save(flush: true)

        ScoreManager.addPoints(user, "KNOWLEDGE", "LEARNING")
        params.clear()
        params.learningSaved = true
        redirect(action: "knowledge", params: params)
    }

    def createReport = {
        def user = findLoggedUser()
        Date date
        String startDate, endDate, projectId, roleId, userId
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")

        if (params.startDate) {

            date = new Date(params.startDate)
            startDate = sdf.format(date)
        }

        if (params.endDate) {
            date = new Date(params.endDate)
            endDate = sdf.format(date)
        }

        if (params.projectId)
            projectId = params.projectId

        if (params.roleId)
            roleId = params.roleId

        if (params.userId)
            userId = params.userId

        def reportRows = databaseService.getReport(startDate, endDate, projectId, roleId, userId, user.company)
        def List report = new ArrayList()


        reportRows.each {
            ReportItem ri = new ReportItem()
            ri.user = it.getAt("user")
            ri.date = it.getAt("date")
            ri.timeSpent = it.getAt("timeSpent")
            ri.project = it.getAt("project")
            ri.role = it.getAt("role")
            ri.assignmentStartDate = it.getAt("assignmentStartDate")
            ri.assignmentEndDate = it.getAt("assignmentEndDate")
            ri.comment = it.getAt("comment")
            report.add(ri)
        }

        FastReportBuilder drb = new FastReportBuilder();
        drb.setTitle("Hourly Report")

        drb.setLeftMargin(20)
                .setRightMargin(20)
                .setTopMargin(20)
                .setBottomMargin(20);

        drb.setDetailHeight(20)
                .setHeaderHeight(30);

        drb.setColumnsPerPage(1);

        AbstractColumn c1 = ColumnBuilder.getNew().setColumnProperty("project", String.class.getName()).setTitle("Project").setWidth(40).build();
        AbstractColumn c2 = ColumnBuilder.getNew().setColumnProperty("user", String.class.getName()).setTitle("User").setWidth(30).build();
        AbstractColumn c3 = ColumnBuilder.getNew().setColumnProperty("role", String.class.getName()).setTitle("Role").setWidth(30).build();
        AbstractColumn c4 = ColumnBuilder.getNew().setColumnProperty("date", Date.class.getName()).setTitle("Date").setWidth(30).build();
        AbstractColumn c5 = ColumnBuilder.getNew().setColumnProperty("timeSpent", Double.class.getName()).setTitle("Time Spent").setWidth(40).setFixedWidth(true).build();
        AbstractColumn c6 = ColumnBuilder.getNew().setColumnProperty("comment", String.class.getName()).setTitle("Comment").setWidth(60).build();

        drb.addColumn(c1);
        drb.addColumn(c3);
        drb.addColumn(c2);
        drb.addColumn(c4);
        drb.addColumn(c5);
        drb.addColumn(c6);

        drb.setUseFullPageWidth(true);

        drb.addGroups(3);
        drb.addFooterVariable(1, 5, DJCalculation.SUM, null);
        drb.addFooterVariable(2, 5, DJCalculation.SUM, null);
        drb.addFooterVariable(3, 5, DJCalculation.SUM, null);
        drb.setGroupLayout(1, GroupLayout.VALUE_IN_HEADER_WITH_HEADERS)
        drb.setPrintColumnNames(true)

        DynamicReport dr = drb.build();

        JRDataSource ds = new JRBeanCollectionDataSource(report);

        File outfile = File.createTempFile("hourly_report", "hourly_report");
        String reportExt
        Boolean excelReport = Boolean.parseBoolean(params.excel);

        if (excelReport) {
            reportExt = "xls"
            JasperPrint jp = DynamicJasperHelper.generateJasperPrint(dr, new ListLayoutManager(), ds);
            exportReportToXLS(jp, outfile.getAbsolutePath());
            response.contentType = 'application/vnd.ms-excel'
        } else {
            reportExt = "pdf"
            JasperPrint jp = DynamicJasperHelper.generateJasperPrint(dr, new ClassicLayoutManager(), ds);
            exportReportToPDF(jp, outfile.getAbsolutePath());
            response.contentType = 'application/pdf'
        }

        FileInputStream ins = new FileInputStream(outfile);
        int inData = ins.read();
        while (inData != -1) {
            response.outputStream.write(inData);
            inData = ins.read();
        }
        ins.close();

        String reportName = "reporte"
        response.setHeader("Content-disposition", "attachment; filename=" + reportName + "." + reportExt);
        response.outputStream.close()

        return false
    }


    def ajaxUpdateUsersAndRoles = {

        def user = findLoggedUser()
        List users = new ArrayList()
        List roles = new ArrayList()

        if (params.projectId) {
            // If we have a project to filter, we filter available roles and users.
            roles = Role.executeQuery("select distinct ro from Role as ro, Assignment as ass where  ass.role = ro and ass.project = ? order by ro.name asc", [Project.get(params.projectId)])
            users = User.executeQuery("select distinct us from User as us, Assignment as ass where  ass.user = us and ass.project = ? order by us.name asc", [Project.get(params.projectId)])
        } else {
            roles = Role.withCriteria() {
                eq("company", user.company)
                order("name", "asc")
            }
            users = User.withCriteria() {
                eq("company", user.company)
                order("name", "asc")
            }
        }

        render(contentType: 'text/json') {
            [
                    'usersData': users.collect { u -> [id: u.id, name: u.name] },
                    'rolesData': roles.collect { r -> [id: r.id, name: r.name] }
            ]
        }
    }

    def ajaxUpdateUsers = {

        def user = findLoggedUser()
        List users = new ArrayList()

        if (params.roleId) {

            // If we have a project to filter, we filter available roles and users.
            if (params.projectId) {
                users = User.executeQuery("select distinct us from User as us, Assignment as ass where  ass.user = us and ass.project = ? and ass.role = ? order by us.name asc", [Project.get(params.projectId), Role.get(params.roleId)])
            } else {
                users = User.executeQuery("select distinct us from User as us, Assignment as ass where  ass.user = us and ass.role = ? order by us.name asc", [Role.get(params.roleId)])
            }

        } else {
            users = User.withCriteria() {
                eq("company", user.company)
                order("name", "asc")
            }
        }

        render(contentType: 'text/json') {
            [
                    'usersData': users.collect { u -> [id: u.id, name: u.name] },
            ]
        }
    }

    private void exportReportToPDF(JasperPrint jp, String path) throws JRException, FileNotFoundException {
        JRExporter exporter = new JRPdfExporter();

        File outputFile = new File(path);
        File parentFile = outputFile.getParentFile();
        if (parentFile != null)
            parentFile.mkdirs();
        FileOutputStream fos = new FileOutputStream(outputFile);

        exporter.setParameter(JRExporterParameter.JASPER_PRINT, jp);
        exporter.setParameter(JRExporterParameter.OUTPUT_STREAM, fos);

        exporter.exportReport();
    }

    private void exportReportToXLS(JasperPrint jp, String path) throws JRException, FileNotFoundException {
        JRExporter exporter = new JRXlsExporter();

        File outputFile = new File(path);
        File parentFile = outputFile.getParentFile();
        if (parentFile != null)
            parentFile.mkdirs();
        FileOutputStream fos = new FileOutputStream(outputFile);

        exporter.setParameter(JRExporterParameter.JASPER_PRINT, jp);
        exporter.setParameter(JRExporterParameter.OUTPUT_STREAM, fos);

        exporter.setParameter(JRXlsExporterParameter.IS_ONE_PAGE_PER_SHEET, Boolean.FALSE);
        exporter.setParameter(JRXlsExporterParameter.IS_REMOVE_EMPTY_SPACE_BETWEEN_ROWS, Boolean.TRUE);
        exporter.setParameter(JRXlsExporterParameter.IS_WHITE_PAGE_BACKGROUND, Boolean.FALSE);
        exporter.setParameter(JRXlsExporterParameter.IS_DETECT_CELL_TYPE, Boolean.TRUE);

        exporter.exportReport();

    }

    /**
     *   Chart company's mood
     */
    def mood = {

        def user = findLoggedUser()
        def userList = User.findAllByCompany(user.company, [sort: 'name'])

        def now = new Date()
        def chosenMonth = params.selectedMonth ? Integer.parseInt(params.selectedMonth) - 1 : now.month
        def chosenYear = params.selectedYear ? Integer.parseInt(params.selectedYear) : now.year + 1900

        // 'selectedUser' should be selected only if requester is PROJECT LEADER or COMPANY OWNER
        if (params.selectedUser) {
            user = User.get(Integer.parseInt(params.selectedUser))
        }

        GregorianCalendar beginningOfMonth = new GregorianCalendar(chosenYear, chosenMonth, 1)
        GregorianCalendar endOfMonth = new GregorianCalendar(chosenYear, chosenMonth, beginningOfMonth.getActualMaximum(GregorianCalendar.DAY_OF_MONTH))

        def mood = UserMood.withCriteria {
            eq("user", user)
            gte("date", beginningOfMonth.getTime())
            lte("date", endOfMonth.getTime())
            eq("company", user.company)
            order('date', 'asc')
        }
        def userMood = mood.collectEntries { UserMood um ->
            [um.date.date, um.value]
        }

        def companyMood = databaseService.getCompanyMood(user, beginningOfMonth.getTime(), endOfMonth.getTime())

        render(view: 'mood', model: [user: user, companyMood: companyMood, userMood: userMood, month: chosenMonth + 1, year: chosenYear, yearList: databaseService.findAvailableYears(), usersList: userList])
    }

    def final COLORS = ['#A2EF00', '#00B945', '#FFEF00', '#88B32D', '#238B49', '#BFB630', '#699B00', '#00782D', '#A69C00', '#BBF73E', '#37DC74', '#FFF340', '#CBF76F', '#63DC90', '#FFF673']

    def final MAX_GANTT_ROWS = 20

    def usersGantt = {
        session['projectColors'] = [:]
        def today = new Date().clearTime()
        def startDate = today - 7
        def endDate = today + 28
        def billings = [0: g.message(code: "default.all"), 1: 'Billable', 2: 'No Billable']
        def modes = Mode.list()
        def skills = Skill.list()
        [start: 0, max: MAX_GANTT_ROWS, startDate: startDate, endDate: endDate, billings: billings, modes: modes, skills: skills, billing: 1]
    }

    def usersGanttData = { UserGanttFilter cmd ->
        def user = findLoggedUser()
        def today = new Date().clearTime()
        def startDate = cmd.startDate ?: today - 7
        def endDate = cmd.endDate ?: today + 28
        String sql = '''select a.id, a.startDate, a.endDate, p.name, u.name, a.description, u.id, r.name, p.id
from Assignment a join a.project p join a.user u join a.role r
where a.startDate <= ?
and a.endDate >= ?
and a.deleted = false
and a.active = true
and p.company = ? '''
        def sqlParams = [endDate, startDate, user.company]
        if (cmd.billing != 0) {
            sql += 'and p.billable = ? '
            sqlParams << (cmd.billing == 1)
        }
        if (cmd.mode) {
            sql += 'and ('
            cmd.mode.eachWithIndex { m, idx ->
                if (idx > 0) {
                    sql += 'or '
                }
                sql += 'p.mode.id = ? '
                sqlParams << m
            }
            sql += ') '
        }
        if (cmd.skill) {
            sql += 'and ('
            cmd.skill.eachWithIndex { m, idx ->
                if (idx > 0) {
                    sql += 'or '
                }
                sql += 'exists (from u.skills s where s.id = ?) '
                sqlParams << m
            }
            sql += ') '
        }
        sql += 'order by u.name, p.name'
        def results = Assignment.executeQuery(sql, sqlParams)
        def map = results.groupBy { it[4] }
        def list = []
        def colorIndex = 0
        def projectColors = session['projectColors']
        if (!projectColors) {
            projectColors = [:]
            session['projectColors'] = projectColors
        }
        map.each { k, v ->
            def record = [id: v[0][6], name: k, series: []]
            v.each {
                def projectId = it[8]
                def projectColor = projectColors[projectId]
                if (!projectColor) {
                    projectColor = COLORS[colorIndex++ % COLORS.size()]
                    projectColors[projectId] = projectColor
                }
                def subrecord = [name: "${it[3]} - ${it[7]}", start: [it[1], startDate].max(), end: [it[2], endDate].min(), color: projectColor]
                record.series << subrecord
            }
            list << record
        }
        if (!list) {
            return list as JSON
        }
        def start = (params.start ?: 0) as int
        start = Math.min(Math.max(start, 0), list.size() - 1)
        def end = Math.min(start + MAX_GANTT_ROWS, list.size()) - 1
        render list[start..end] as JSON
    }
}

class UserGanttFilter {
    int start
    int max
    Date startDate
    Date endDate
    long billing
    long[] mode
    long[] skill
}
