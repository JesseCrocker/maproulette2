// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.models.dal

import java.sql.Connection
import javax.inject.{Inject, Singleton}

import anorm._
import anorm.SqlParser._
import org.maproulette.Config
import org.maproulette.cache.CacheManager
import org.maproulette.exception.UniqueViolationException
import org.maproulette.models._
import org.maproulette.permissions.Permission
import org.maproulette.session.{Group, SearchParameters, User}
import org.maproulette.session.dal.UserGroupDAL
import play.api.Logger
import play.api.db.Database
import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable.ListBuffer

/**
  * Specific functions for the project data access layer
  *
  * @author cuthbertm
  */
@Singleton
class ProjectDAL @Inject() (override val db:Database,
                            childDAL:ChallengeDAL,
                            surveyDAL:SurveyDAL,
                            userGroupDAL: UserGroupDAL,
                            override val permission:Permission)
  extends ParentDAL[Long, Project, Challenge] {

  // manager for the cache of the projects
  override val cacheManager = new CacheManager[Long, Project]
  // table name for projects
  override val tableName: String = "projects"
  // table name for project children, challenges
  override val childTable: String = "challenges"
  // anorm row parser for child as defined by the challenge data access layer
  override val childParser = childDAL.parser

  // The anorm row parser for the Project to map database records directly to Project objects
  override val parser: RowParser[Project] = {
    get[Long]("projects.id") ~
      get[String]("projects.name") ~
      get[Option[String]]("projects.description") ~
      get[Boolean]("projects.enabled") map {
      case id ~ name ~ description ~ enabled =>
        new Project(id, name, description, userGroupDAL.getProjectGroups(id, User.superUser), enabled)
    }
  }

  val pointParser = {
    long("id") ~
      str("name") ~
      str("blurb") ~
      str("location") ~
      int("difficulty") ~
      int("challenge_type") map {
      case id ~ name ~ instruction ~ location ~ difficulty ~ challengeType =>
        val locationJSON = Json.parse(location)
        val coordinates = (locationJSON \ "coordinates").as[List[Double]]
        val point = Point(coordinates(1), coordinates.head)
        ClusteredPoint(id, name, point, instruction, difficulty, challengeType)
    }
  }

  /**
    * Inserts a new project object into the database
    *
    * @param project The project to insert into the database
    * @return The object that was inserted into the database. This will include the newly created id
    */
  override def insert(project: Project, user:User)(implicit c:Option[Connection]=None): Project = {
    this.permission.hasWriteAccess(project, user)
    this.cacheManager.withOptionCaching { () =>
      // only super users can enable or disable projects
      val setProject = if (!user.isSuperUser || user.adminForProject(project.id)) {
        Logger.warn(s"User [${user.name} - ${user.id}] is not a super user and cannot enable or disable projects")
        project.copy(enabled = false)
      } else {
        project
      }
      val newProject = this.withMRTransaction { implicit c =>
        SQL"""INSERT INTO projects (name, description, enabled)
              VALUES (${setProject.name}, ${setProject.description}, ${setProject.enabled})
              ON CONFLICT(LOWER(name)) DO NOTHING RETURNING *""".as(parser.*).headOption
      }
      newProject match {
        case Some(proj) =>
          // todo: this should be in the above transaction, but for some reason the fkey won't allow it
          db.withTransaction { implicit c =>
            // Every new project needs to have a admin group created for them
            this.userGroupDAL.createGroup(proj.id, proj.name + "_Admin", Group.TYPE_ADMIN, User.superUser)
            Some(proj)
          }
        case None =>
          throw new UniqueViolationException(s"Project with name ${project.name} already exists in the database.")
      }
    }.get
  }

  /**
    * Updates a project in the database
    *
    * @param updates A json object containing all the fields that are too be updated.
    * @param id The id of the object that you are updating.
    * @return An optional object, it will return None if no object found with a matching id that was supplied.
    */
  override def update(updates:JsValue, user:User)(implicit id:Long, c:Option[Connection]=None): Option[Project] = {
    this.cacheManager.withUpdatingCache(Long => retrieveById) { implicit cachedItem =>
      this.permission.hasWriteAccess(cachedItem, user)
      this.withMRTransaction { implicit c =>
        val name = (updates \ "name").asOpt[String].getOrElse(cachedItem.name)
        val description = (updates \ "description").asOpt[String].getOrElse(cachedItem.description.getOrElse(""))
        val enabled = (updates \ "enabled").asOpt[Boolean] match {
          case Some(e) if !user.isSuperUser && !user.adminForProject(id) =>
            Logger.warn(s"User [${user.name} - ${user.id}] is not a super user and cannot enable or disable projects")
            cachedItem.enabled
          case Some(e) => e
          case None => cachedItem.enabled
        }

        SQL"""UPDATE projects SET name = $name,
              description = $description,
              enabled = $enabled
              WHERE id = $id RETURNING *""".as(this.parser.*).headOption
      }
    }
  }

  /**
    * Gets a list of all projects that are specific managed by the supplied user
    *
    * @param user The user executing the request
    * @return A list of projects managed by the user
    */
  def listManagedProjects(user:User, limit:Int = Config.DEFAULT_LIST_SIZE, offset:Int = 0, onlyEnabled:Boolean=false,
                          searchString:String="")(implicit c:Option[Connection]=None) : List[Project] = {
    if (user.isSuperUser) {
      this.list(limit, offset, onlyEnabled, searchString)
    } else {
      this.withMRConnection { implicit c =>
        if (user.groups.isEmpty) {
          List.empty
        } else {
          val query =
            s"""SELECT p.* FROM projects p
              INNER JOIN groups g ON g.project_id = p.id
              WHERE g.id IN ({ids}) ${this.searchField("p.name")} ${this.enabled(onlyEnabled)}
              LIMIT ${this.sqlLimit(limit)} OFFSET {offset}"""
          SQL(query).on('ss -> this.search(searchString), 'offset -> ParameterValue.toParameterValue(offset),
            'ids -> user.groups.map(_.id))
            .as(this.parser.*)
        }
      }
    }
  }

  /**
    * Gets all the counts of challenges and surveys for each available project
    *
    * @param user The user executing the request, will limit the response to only accesible projects
    * @param limit To limit the number of project counts to return
    * @param offset Paging starting at 0
    * @param onlyEnabled Whether to list only enabled projects
    * @param searchString To search by project name
    * @param c implicit connection, if not supplied will open new connection
    * @return A map of project ids to tuple with number of challenge and survey children for the project
    */
  def getChildrenCounts(user:User, limit:Int = Config.DEFAULT_LIST_SIZE, offset:Int = 0, onlyEnabled:Boolean=false,
                        searchString:String="")(implicit c:Option[Connection]=None) : Map[Long, (Int, Int)] = {
    this.withMRConnection { implicit c =>
      val parser = for {
        id <- long("id")
        challenges <- int("challenges")
        surveys <- int("surveys")
      } yield (id, challenges, surveys)
      val query = s"""SELECT p.id,
                      SUM(CASE c.challenge_type WHEN 1 THEN 1 ELSE 0 END) AS challenges,
                      SUM(CASE c.challenge_type WHEN 4 THEN 1 ELSE 0 END) AS surveys
                    FROM projects p
                    INNER JOIN groups g ON g.project_id = p.id
                    INNER JOIN challenges c ON c.parent_id = p.id
                    WHERE (1=${if (user.isSuperUser) { 1 } else { 0 }} OR g.id IN ({ids}))
                    ${this.searchField("p.name")} ${this.enabled(onlyEnabled, "p")}
                    GROUP BY p.id
                    LIMIT ${this.sqlLimit(limit)} OFFSET $offset"""
      SQL(query).on('ss -> this.search(searchString), 'ids -> user.groups.map(_.id)).as(parser.*)
        .map(v => v._1 -> (v._2, v._3)).toMap
    }
  }

  /**
    * Retrieves the clustered json points for a searched set of challenges
    *
    * @param params search parameters
    * @return
    */
  def getSearchedClusteredPoints(params: SearchParameters)
                                (implicit c:Option[Connection]=None) : List[ClusteredPoint] = {
    this.withMRConnection { implicit c =>
      val parameters = new ListBuffer[NamedParameter]()
      // the named parameter for the challenge name
      parameters += ('cs -> this.search(params.challengeSearch))
      parameters += ('ps -> this.search(params.projectSearch))
      // search by tags if any
      val challengeTags = if (params.challengeTags.nonEmpty) {
        val tags = params.challengeTags.zipWithIndex.map{
          case (v, i) =>
            parameters += (s"tag_$i" -> this.search(v))
            s"t.name LIKE {tag_$i}"
        }
        (
          """
            |INNER JOIN tags_on_challenges tc ON tc.challenge_id = c.id
            |INNER JOIN tags t ON t.id = tc.tag_id
          """.stripMargin,
          s"AND ${tags.mkString(" OR ")}"
          )
      } else {
        ("", "")
      }
      // search by location bounding box
      val locationClause = params.location match {
        case Some(l) => s"AND c.location @ ST_MakeEnvelope(${l.left}, ${l.bottom}, ${l.right}, ${l.top}, 4326)"
        case None => ""
      }
      val query = s"""
          SELECT c.id, c.name, c.blurb, ST_AsGeoJSON(c.location) AS location,
                  c.difficulty, c.challenge_type
          FROM challenges c
          INNER JOIN projects p ON p.id = c.parent_id
          ${challengeTags._1}
          WHERE c.location IS NOT NULL AND (
            SELECT COUNT(*) FROM tasks
            WHERE parent_id = c.id AND status IN (${Task.STATUS_CREATED},${Task.STATUS_SKIPPED},${Task.STATUS_TOO_HARD})) > 0
          ${this.searchField("c.name", "AND", "cs")}
          ${this.searchField("p.name", "AND", "ps")}
          ${this.enabled(params.challengeEnabled, "c")} ${this.enabled(params.projectEnabled, "p")}
          ${if (params.projectId.isDefined && params.projectId.get > 0) {s" AND c.parent_id = ${params.projectId.get}"} else {""}}
          $locationClause
          ${challengeTags._2}
         """
        this.sqlWithParameters(query, parameters).as(this.pointParser.*)
    }
  }

  /**
    * Retrieves the clustered json for challenges
    *
    * @param projectId The project id for the requested challenges, if None, then retrieve all challenges
    * @param challengeIds A list of challengeId's that you can filter the result by
    * @param enabledOnly Show only the enabled challenges
    * @return A list of ClusteredPoint objects
    */
  def getClusteredPoints(projectId:Option[Long]=None, challengeIds:List[Long]=List.empty,
                              enabledOnly:Boolean=true)(implicit c:Option[Connection]=None) : List[ClusteredPoint] = {
    this.withMRConnection { implicit c =>
      SQL"""SELECT c.id, c.name, c.blurb, ST_AsGeoJSON(c.location) AS location,
                    c.difficulty, c.challenge_type
              FROM challenges c
              INNER JOIN projects p ON p.id = c.parent_id
              WHERE c.location IS NOT NULL AND (
                SELECT COUNT(*) FROM tasks
                WHERE parent_id = c.id AND status IN (${Task.STATUS_CREATED},${Task.STATUS_SKIPPED},${Task.STATUS_TOO_HARD})) > 0
              #${this.enabled(enabledOnly, "c")} #${this.enabled(enabledOnly, "p")}
              #${if(projectId.isDefined) { s" AND c.parent_id = ${projectId.get}"} else { "" }}
              #${if(challengeIds.nonEmpty) { s" AND c.id IN (${challengeIds.mkString(",")})"} else { "" }}
        """.as(this.pointParser.*)
    }
  }
}
