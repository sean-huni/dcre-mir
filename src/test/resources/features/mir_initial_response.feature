# MIR composes the initial ACK/NACK for one mandate arrival and stages it into the
# OnHost mandate response directory (onhost-resp-man). ACK means accepted-by-DCRE;
# per-record rejections travel as REJ detail lines whose reason is either an MRV
# validation FAIL or the MAS SCORE_DECLINED (FAIL_SCORE_BELOW_THRESHOLD, R-08). A
# file-fatal arrival is NACKed outright. StagedWrite makes a re-run a restart no-op (R-05).
# The response record layout is SYNTHETIC (A-57 class), pending the mandate response copybook.
@mir
Feature: MIR initial mandate response back to the OnHost client

  Scenario: A fully accepted arrival is acknowledged with no rejections
    Given a mandate arrival of 5 instructions that all passed validation and score
    When the initial mandate response job runs
    Then the mandate response file acknowledges 5 of 5 instructions
    And the mandate response file carries no rejection details

  Scenario: A partial arrival itemizes both validation and score-decline rejections
    Given a mandate arrival of 5 instructions where these rows were rejected:
      | sequence | source     | reason                     |
      | 1        | validation | FAIL_ACCOUNT_NOT_FOUND     |
      | 3        | score      | SCORE_DECLINED             |
    When the initial mandate response job runs
    Then the mandate response file acknowledges 3 of 5 instructions
    And the mandate response file lists the rejections:
      | sequence | reason                      |
      | 1        | FAIL_ACCOUNT_NOT_FOUND      |
      | 3        | FAIL_SCORE_BELOW_THRESHOLD  |

  Scenario: A file-rejected-by-policy arrival is a whole-file NACK itemizing every rejection
    Given a mandate arrival of 4 instructions where these rows were rejected:
      | sequence | source     | reason                 |
      | 2        | validation | FAIL_STRUCTURE         |
      | 4        | score      | SCORE_DECLINED         |
    When the initial mandate response job runs for a business-file-rejected arrival
    Then the mandate response file is a NACK for 4 instructions citing "FILE_REJECTED_BY_POLICY"
    And the mandate response file lists the rejections:
      | sequence | reason                      |
      | 2        | FAIL_STRUCTURE              |
      | 4        | FAIL_SCORE_BELOW_THRESHOLD  |

  Scenario: A file-fatal arrival is rejected outright with a NACK
    Given a file-fatal mandate arrival declaring 3 instructions with no verdicts recorded
    When the initial mandate response job runs citing the fatal reason "spine count 10 != declared 11"
    Then the mandate response file is a NACK for 3 instructions citing "spine count 10 != declared 11"

  Scenario: The staged response filename is durably recorded in the man_initial_response ledger
    Given a mandate arrival of 5 instructions that all passed validation and score
    When the initial mandate response job runs
    Then the mandate response file acknowledges 5 of 5 instructions
    And the response filename is recorded in man_initial_response as an ACK of 5 of 5

  Scenario: Re-running the response job never rewrites an existing response
    Given a mandate arrival of 5 instructions where these rows were rejected:
      | sequence | source     | reason                 |
      | 1        | validation | FAIL_ACCOUNT_NOT_FOUND |
    When the initial mandate response job runs
    And the initial mandate response job runs again for the same arrival
    Then the mandate response file on disk is unchanged
    And the mandate response file acknowledges 4 of 5 instructions
