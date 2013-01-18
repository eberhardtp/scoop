import com.gravitydev.scoop._

object Repo {
  
  implicit object issueStatus extends SqlCustomType [IssueStatus, Int] (IssueStatuses.forId _, _.id)
 
  /** type annotation is not required in scala 2.10 */ 
  case class issues () extends Table[issues](issues) {
    val id          = col[Long]         ("id")
    val project_id  = col[Long]         ("project_id")
    val item_id     = col[Long]         ("item_id")
    val title       = col[String]       ("title")
    val description = col[String]       ("description")
    val status      = col[IssueStatus]  ("status", cast = "status")
    val reported_by = col[Long]         ("reported_by")
    val assigned_to = col[Long]         ("assigned_to")   nullable
    val release_id  = col[Long]         ("release_id")    nullable
    
    val * = id ~ project_id
  }
  
  case class users () extends Table[users](users) {
    val id          = col[Long]         ("id")
    def first_name  = col[String]       ("first_name")
    def last_name   = col[String]       ("last_name")
    def email       = col[String]       ("email")
  }
  
  sealed abstract class IssueStatus(val id: Int)
  object IssueStatuses {
    def forId (x: Int) = if (x==1) Open else Closed
    object Open extends IssueStatus(1)
    object Closed extends IssueStatus(2)
  }
  
  case class User (
    id:   Long,
    name: String
  )
  
  case class Issue (
    id:       Long,
    status:   IssueStatus,
    reporter: User,
    assignee: Option[User]
  )
  
  object Parsers {
    def user (u: users) = u.id ~ u.first_name ~ u.last_name >> {(i,f,l) => User(i,f+" "+l)}
    
    //val rep = user(users)
    
    //def issue (i: Issues, r: Users, a: Users) = i.id ~ i.status ~ user(r).as(prefix="reporter_") ~ opt(user(a).as(prefix="assignee_")) map {(i,s,rep,assignee) => Issue(i,s,rep,assignee)}
  }
  
}

