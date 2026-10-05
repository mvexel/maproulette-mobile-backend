/*
 * This scripts generates a CSV file that outputs the permissions each user
 * has on each project, with one line representing a single user/project
 * combination. It can be useful for determining whether real-world user
 * permissions are impacted by changes to the security system.
 *
 * Run with:
 *
 * ```
 * cat scripts/permission_dump.scala | sbt console
 * ```
 *
 * passing in whatever options to sbt you'd normally use, such as a `-Dconfig.file`, etc.
 */
import javax.inject.{Inject}
import java.io._
import org.maproulette.framework.service.ServiceManager
import org.maproulette.permissions.Permission
import org.maproulette.framework.model._
import org.maproulette.framework.psql.{Query, Order}
import play.api._

val env     = Environment(new java.io.File("."), this.getClass.getClassLoader, Mode.Dev)
val context = ApplicationLoader.Context.create(env)
val loader  = ApplicationLoader(context)
val app     = loader.load(context)
Play.start(app)

val serviceManager = app.injector.instanceOf(
  Class.forName("org.maproulette.framework.service.ServiceManager")
).asInstanceOf[org.maproulette.framework.service.ServiceManager]

val permission = app.injector.instanceOf(
  Class.forName("org.maproulette.permissions.Permission")
).asInstanceOf[org.maproulette.permissions.Permission]

val allUsers = serviceManager.user.query(
  Query.simple(List(), order=Order > ("id", Order.ASC)),
  User.superUser
)
val allProjects = serviceManager.project.query(
  Query.simple(List(), order=Order > ("id", Order.ASC))
)

val csv = new PrintWriter(new File("user_permission_dump.csv"))
csv.write("UserId,ProjectId,Read,Write,Admin\n")

allUsers.foreach { u =>
  allProjects.foreach { p =>
    csv.write(s"${u.id},${p.id},")
    try {
      permission.hasObjectReadAccess(p, u)
      csv.write("T,")
    }
    catch {
      case e: Exception => csv.write("F,")
    }

    try {
      permission.hasObjectWriteAccess(p, u)
      csv.write("T,")
    }
    catch {
      case e: Exception => csv.write("F,")
    }

    try {
      permission.hasObjectAdminAccess(p, u)
      csv.write("T\n")
    }
    catch {
      case e: Exception => csv.write("F\n")
    }
  }
}
csv.close()

