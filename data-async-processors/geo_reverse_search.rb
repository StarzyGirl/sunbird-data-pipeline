require 'logger'
require 'elasticsearch'
require 'pry'
require 'hashie'
require 'geocoder'

class Location
    SLEEP_INTERVAL=0.2
    ADDRESS_COMPONENTS_MAPPINGS={
      locality: :locality,
      district: :administrative_area_level_2,
      state: :administrative_area_level_1,
      country: :country
    }
    attr_reader :loc,:locality,:district,:state,:country
    def initialize(loc)
      @loc=loc
      @results=reverse_search
      set_identity
    end
    private
    def reverse_search
      sleep(SLEEP_INTERVAL)
      Geocoder.search(loc)
    end
    def set_identity
      @locality = get_name(:locality)
      @district = get_name(:district)
      @state = get_name(:state)
      @country = get_name(:country)
      raise 'Location not set!' if(@locality&&@district&&@state&&@country).nil?
    end
    def get_name(type)
      begin
        result = @results.find{|r|!r.address_components_of_type(ADDRESS_COMPONENTS_MAPPINGS[type]).empty?}
        result.address_components_of_type(ADDRESS_COMPONENTS_MAPPINGS[type]).first['long_name']
      rescue => e
        ""
      end
    end
  end

module Processors
  class ReverseSearch
    def self.perform(index="ecosystem-*",type="events_v1")
      begin
      file = "#{ENV['EP_LOG_DIR']}/#{self.name.gsub('::','')}.log"
      logger = Logger.new(file)
      logger.info "STARTING REVERSE SEARCH"
      @client = ::Elasticsearch::Client.new(host:ENV['ES_HOST']||'localhost:9200',log: false)
      response = @client.search({
        index: index,
        type: type,
        size: 1000,
        body: {
          "query"=> {
            "constant_score" => {
              "filter" => {
                "and"=> [
                  {
                    "term"=> {
                      "eid"=>"GE_GENIE_START"
                    }
                  },
                  {
                    "missing" => {
                      "field" => "edata.eks.ldata.country",
                      "existence" => true,
                      "null_value" => true
                    }
                  }
                ]
              }
            }
          }
        }
      })
      response = Hashie::Mash.new response
      logger.info "FOUND #{response.hits.hits.count} hits."
      response.hits.hits.each do |hit|
        result = nil
        if _loc=hit._source.edata.eks.loc
          _id = hit._id
          logger.info "LOC #{_loc}"
          location = Location.new(_loc)
          ldata = {
            locality: location.locality,
            district: location.district,
            state: location.state,
            country: location.country
          }
          logger.info "LDATA #{ldata.to_json}"
          edata = {
            edata:{
              eks: ldata
            }
          }
          result = @client.update({
            index: hit._index,
            type: hit._type,
            id: _id,
            body: {
              doc: edata
            }
          })
          logger.info "RESULT #{result.to_json}"
          if(ENV['ENV']=='test')
            _index = 'test-identities'
          else
            _index = 'ecosystem-identities'
          end
          logger.info "DEVICE #{hit._source.did}"
        end
        # yield _loc,ldata,hit
        result = @client.index(
          index: _index,
          type: 'devices_v1',
          body: {
            ts: hit._source.ts,
            "@timestamp" => hit._source["@timestamp"],
            did: hit._source.did,
            loc: _loc,
            ldata: ldata,
            dspec: hit._source.edata.eks.dspec
          }
        )
        logger.info "devices_v1 UPDATE #{result.to_json}"
      end
      logger.info "ENDING REVERSE SEARCH"
     rescue => e
      logger.error e
     end
    end
  end
end
