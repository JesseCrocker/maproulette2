// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.models

import play.api.data._
import play.api.data.Forms._
import org.maproulette.actions.{ItemType, SurveyType}
import play.api.libs.json.{Json, Reads, Writes}

case class Answer(id:Long = -1, answer:String)

/**
  * A survey is a special kind of challenge that shows you some geometry on the map and then asks
  * you a multiple choice question about the geometry. So something like, "Is this POI valid?"
  * A) No
  * B) Yes
  * C) Not Sure
  * D) Other
  *
  * The multiple choice answers can be anything that the user wants it to be.
  *
  * @author cuthbertm
  */
case class Survey(challenge:Challenge, answers:List[Answer]) extends BaseObject[Long] {
  override def name: String = challenge.name
  override def id: Long = challenge.id
  def question : String = challenge.instruction
  override val itemType: ItemType = SurveyType()
}

object Survey {
  implicit val answerWrites: Writes[Answer] = Json.writes[Answer]
  implicit val answerReads: Reads[Answer] = Json.reads[Answer]
  implicit val challengeWrites: Writes[Challenge] = Challenge.challengeWrites
  implicit val challengeReads: Reads[Challenge] = Challenge.challengeReads

  implicit val surveyWrites: Writes[Survey] = Json.writes[Survey]
  implicit val surveyReads: Reads[Survey] = Json.reads[Survey]

  val surveyForm = Form(
    mapping(
      "challenge" -> Challenge.challengeForm.mapping,
      "answers" -> list(
        mapping(
          "id" -> default(longNumber,-1L),
          "answer" -> nonEmptyText
        )(Answer.apply)(Answer.unapply)
      )
    )(Survey.apply)(Survey.unapply)
  )

  def emptySurvey(parentId:Long) : Survey = Survey(Challenge.emptyChallenge(parentId), List.empty)
}
